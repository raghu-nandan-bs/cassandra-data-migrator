/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.cdm.job;

import com.datastax.cdm.cql.statement.OriginSelectByPartitionRangeStatement;
import com.datastax.cdm.cql.statement.TargetSelectByPKStatement;
import com.datastax.cdm.cql.statement.TargetSelectByPartitionRangeStatement;
import com.datastax.cdm.data.CqlData;
import com.datastax.cdm.data.DataUtility;
import com.datastax.cdm.data.EnhancedPK;
import com.datastax.cdm.data.PKFactory;
import com.datastax.cdm.data.Record;
import com.datastax.cdm.feature.ConstantColumns;
import com.datastax.cdm.feature.ExplodeMap;
import com.datastax.cdm.feature.Featureset;
import com.datastax.cdm.feature.Guardrail;
import com.datastax.cdm.properties.KnownProperties;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.type.DataType;
import org.apache.logging.log4j.ThreadContext;
import org.apache.spark.SparkConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

public class DiffJobSession extends CopyJobSession {
    protected final Boolean autoCorrectMissing;
    protected final Boolean autoCorrectMismatch;
    private final boolean isCounterTable;
    private final boolean forceCounterWhenMissing;
    private final List<String> targetColumnNames;
    private final List<DataType> targetColumnTypes;
    private final List<DataType> originColumnTypes;
    private final int explodeMapKeyIndex;
    private final int explodeMapValueIndex;

    private final boolean skipValueCheck;
    private final List<Integer> constantColumnIndexes;
    public Logger logger = LoggerFactory.getLogger(this.getClass().getName());
    boolean logDebug = logger.isDebugEnabled();
    boolean logTrace = logger.isTraceEnabled();

    public DiffJobSession(CqlSession originSession, CqlSession targetSession, SparkConf sc) {
        super(originSession, targetSession, sc);
        this.jobCounter.setRegisteredTypes(JobCounter.CounterType.READ, JobCounter.CounterType.VALID, JobCounter.CounterType.MISMATCH, JobCounter.CounterType.CORRECTED_MISMATCH, JobCounter.CounterType.MISSING, JobCounter.CounterType.CORRECTED_MISSING, JobCounter.CounterType.SKIPPED);
        skipValueCheck  = propertyHelper.getBoolean(KnownProperties.SKIP_VALUE_COMPARISON);
        autoCorrectMissing = propertyHelper.getBoolean(KnownProperties.AUTOCORRECT_MISSING);
        logger.info("PARAM -- Autocorrect Missing: {}", autoCorrectMissing);

        autoCorrectMismatch = propertyHelper.getBoolean(KnownProperties.AUTOCORRECT_MISMATCH);
        logger.info("PARAM -- Autocorrect Mismatch: {}", autoCorrectMismatch);

        this.isCounterTable = this.originSession.getCqlTable().isCounterTable();
        this.forceCounterWhenMissing = propertyHelper.getBoolean(KnownProperties.AUTOCORRECT_MISSING_COUNTER);
        this.targetColumnNames = this.targetSession.getCqlTable().getColumnNames(false);
        this.targetColumnTypes = this.targetSession.getCqlTable().getColumnCqlTypes();
        this.originColumnTypes = this.originSession.getCqlTable().getColumnCqlTypes();

        ConstantColumns constantColumnsFeature = (ConstantColumns) this.targetSession.getCqlTable().getFeature(Featureset.CONSTANT_COLUMNS);
        if (null != constantColumnsFeature && constantColumnsFeature.isEnabled()) {
            constantColumnIndexes = constantColumnsFeature.getNames().stream()
                    .map(targetColumnNames::indexOf)
                    .collect(Collectors.toList());
            if (logDebug) logger.debug("Constant Column Indexes {}", this.constantColumnIndexes);
        } else {
            constantColumnIndexes = Collections.emptyList();
        }

        ExplodeMap explodeMapFeature = (ExplodeMap) this.targetSession.getCqlTable().getFeature(Featureset.EXPLODE_MAP);
        if (null != explodeMapFeature && explodeMapFeature.isEnabled()) {
            this.explodeMapKeyIndex = this.targetSession.getCqlTable().indexOf(explodeMapFeature.getKeyColumnName());
            this.explodeMapValueIndex = this.targetSession.getCqlTable().indexOf(explodeMapFeature.getValueColumnName());
            if (logDebug)
                logger.debug("Explode Map KeyIndex={}, ValueIndex={}", this.explodeMapKeyIndex, this.explodeMapValueIndex);
        } else {
            this.explodeMapKeyIndex = -1;
            this.explodeMapValueIndex = -1;
        }

        logger.info("CQL -- origin select: {}", this.originSession.getOriginSelectByPartitionRangeStatement().getCQL());
        logger.info("CQL -- target select: {}", this.targetSession.getTargetSelectByPKStatement().getCQL());
        logger.info("CQL -- target upsert: {}", this.targetSession.getTargetUpsertStatement().getCQL());
    }

    @Override
    public void processSlice(SplitPartitions.Partition slice) {
        this.getDataAndDiff(slice.getMin(), slice.getMax());
    }

    public void getDataAndDiff(BigInteger min, BigInteger max) {
        ThreadContext.put(THREAD_CONTEXT_LABEL, getThreadLabel(min, max));
        logger.info("ThreadID: {} Processing min: {} max: {}", Thread.currentThread().getId(), min, max);
        boolean done = false;
        int maxAttempts = maxRetries + 1;
        for (int attempts = 1; attempts <= maxAttempts && !done; attempts++) {
            try {
                jobCounter.threadReset();

                PKFactory pkFactory = originSession.getPKFactory();
                OriginSelectByPartitionRangeStatement originSelectByPartitionRangeStatement = originSession.getOriginSelectByPartitionRangeStatement();
                TargetSelectByPartitionRangeStatement targetSelectByPartitionRangeStatement = targetSession.getTargetSelectByPartitionRangeStatement();

                ResultSet originResultSet = originSelectByPartitionRangeStatement.execute(originSelectByPartitionRangeStatement.bind(min, max));
                ResultSet targetResultSet = targetSelectByPartitionRangeStatement.execute(targetSelectByPartitionRangeStatement.bind(min, max));
                TargetSelectByPKStatement targetSelectByPKStatement = targetSession.getTargetSelectByPKStatement();
                Integer fetchSizeInRows = originSession.getCqlTable().getFetchSizeInRows();

                HashMap<EnhancedPK, Row> targetRowsInSlice = new HashMap<>();
                StreamSupport.stream(targetResultSet.spliterator(), false).forEach(targetRow -> {
                    EnhancedPK pk = pkFactory.getTargetPK(targetRow);
                    targetRowsInSlice.put(pk, targetRow);
                });
                logger.debug("created map of size {} with records from target DB",targetRowsInSlice.size());

                List<Record> recordsToDiff = new ArrayList<>(fetchSizeInRows);
                StreamSupport.stream(originResultSet.spliterator(), false).forEach(originRow -> {
                    rateLimiterOrigin.acquire(1);
                    Record record = new Record(pkFactory.getTargetPK(originRow), originRow, null);
                    jobCounter.threadIncrement(JobCounter.CounterType.READ);

                    if (originSelectByPartitionRangeStatement.shouldFilterRecord(record)) {
                        jobCounter.threadIncrement(JobCounter.CounterType.SKIPPED);
                    } else {
                        for (Record r : pkFactory.toValidRecordList(record)) {

                            if (guardrailEnabled) {
                                String guardrailCheck = guardrailFeature.guardrailChecks(r);
                                if (guardrailCheck != null && guardrailCheck != Guardrail.CLEAN_CHECK) {
                                    logger.error("Guardrails failed for PrimaryKey {}; {}", r.getPk(), guardrailCheck);
                                    jobCounter.threadIncrement(JobCounter.CounterType.SKIPPED);
                                    continue;
                                }
                            }

                            rateLimiterTarget.acquire(1);
                            // CompletionStage<AsyncResultSet> targetResult = targetSelectByPKStatement.getAsyncResult(r.getPk());
                            Row targetResult = targetRowsInSlice.get(r.getPk());
                            if (null == targetResult) {
                                r.setTargetRow(null);
                                recordsToDiff.add(r);
                                // Donot skip, this corresponds missing record in target
                                //logger.error("unable to find record in target for pk: {}",r.getPk());
                                //jobCounter.threadIncrement(JobCounter.CounterType.SKIPPED);
                            } else {
                                // r.setAsyncTargetRow(targetResult);
                                r.setTargetRow(targetResult);
                                recordsToDiff.add(r);
                                if (recordsToDiff.size() > fetchSizeInRows) {
                                    diffAndClear(recordsToDiff);
                                }
                            } // targetRecord!=null
                        } // recordSet iterator
                    } // shouldFilterRecord
                });
                diffAndClear(recordsToDiff);
                done = true;
            } catch (Exception e) {
                logger.error("Error with PartitionRange -- ThreadID: {} Processing min: {} max: {} -- Attempt# {}",
                        Thread.currentThread().getId(), min, max, attempts, e);
                if (attempts == maxAttempts) {
                    logFailedPartitionsInFile(partitionFile, min, max);
                }
            } finally {
                jobCounter.globalIncrement();
                printCounts(false);
            }
        }
    }

    private void diffAndClear(List<Record> recordsToDiff) {
        for (Record record : recordsToDiff) {
            try {
                diff(record);
            } catch (Exception e) {
                logger.error("Could not perform diff for key {}: {}\n{}", record.getPk(), e, e.getStackTrace());
            }
        }
        recordsToDiff.clear();
    }

    private void diff(Record record) {
        EnhancedPK originPK = record.getPk();
        Row originRow = record.getOriginRow();
        Row targetRow = record.getTargetRow();

        if (targetRow == null) {
            jobCounter.threadIncrement(JobCounter.CounterType.MISSING);
            logger.error("Missing target row found for key: {}", record.getPk());
            if (autoCorrectMissing && isCounterTable && !forceCounterWhenMissing) {
                logger.error("{} is true, but not Inserting as {} is not enabled; key : {}", KnownProperties.AUTOCORRECT_MISSING, KnownProperties.AUTOCORRECT_MISSING_COUNTER, record.getPk());
                return;
            }

            //correct data
            if (autoCorrectMissing) {
                rateLimiterTarget.acquire(1);
                logger.info("Attempting to insert data into target: {}", record.getPk());
                targetSession.getTargetUpsertStatement().putRecord(record);
                jobCounter.threadIncrement(JobCounter.CounterType.CORRECTED_MISSING);
                logger.error("Inserted missing row in target: {}", record.getPk());
            }
            return;
        }
        if (!skipValueCheck) {
            String diffData = isDifferent(originPK, originRow, targetRow);
            if (!diffData.isEmpty()) {
                jobCounter.threadIncrement(JobCounter.CounterType.MISMATCH);
                logger.error("Mismatch row found for key: {} Mismatch: {}", record.getPk(), diffData);

                if (autoCorrectMismatch) {
                    rateLimiterTarget.acquire(1);
                    targetSession.getTargetUpsertStatement().putRecord(record);
                    jobCounter.threadIncrement(JobCounter.CounterType.CORRECTED_MISMATCH);
                    logger.error("Corrected mismatch row in target: {}", record.getPk());
                }
            } else {
                jobCounter.threadIncrement(JobCounter.CounterType.VALID);
            }
            jobCounter.threadIncrement(JobCounter.CounterType.VALID);
        }
    }

    private String isDifferent(EnhancedPK pk, Row originRow, Row targetRow) {
        StringBuffer diffData = new StringBuffer();
        IntStream.range(0, targetColumnNames.size()).parallel().forEach(targetIndex -> {
            String previousLabel = ThreadContext.get(THREAD_CONTEXT_LABEL);
            try {
                ThreadContext.put(THREAD_CONTEXT_LABEL, pk + ":" + targetColumnNames.get(targetIndex));
                Object origin = null;
                int originIndex = -2; // this to distinguish default from indexOf result
                Object targetAsOriginType = null;
                try {
                    if (constantColumnIndexes.contains(targetIndex)) {
                        if (logTrace)
                            logger.trace("PK {}, targetIndex {} skipping constant column {}", pk, targetIndex, targetColumnNames.get(targetIndex));
                        return; // nothing to compare in origin
                    }
                    targetAsOriginType = targetSession.getCqlTable().getAndConvertData(targetIndex, targetRow);
                    originIndex = targetSession.getCqlTable().getCorrespondingIndex(targetIndex);
                    if (originIndex >= 0) {
                        origin = originSession.getCqlTable().getData(originIndex, originRow);
                        if (logTrace)
                            logger.trace("PK {}, targetIndex {} column {} using value from origin table at index {}: {}", pk, targetIndex, targetColumnNames.get(targetIndex), originIndex, origin);
                    } else if (targetIndex == explodeMapKeyIndex) {
                        origin = pk.getExplodeMapKey();
                        if (logTrace)
                            logger.trace("PK {}, targetIndex {} column {} using explodeMapKey stored on PK: {}", pk, targetIndex, targetColumnNames.get(targetIndex), origin);
                    } else if (targetIndex == explodeMapValueIndex) {
                        origin = pk.getExplodeMapValue();
                        if (logTrace)
                            logger.trace("PK {}, targetIndex {} column {} using explodeMapValue stored on PK: {}", pk, targetIndex, targetColumnNames.get(targetIndex), origin);
                    } else {
                        throw new RuntimeException("Target column \"" + targetColumnNames.get(targetIndex) + "\" at index " + targetIndex + " cannot be found on Origin, and is neither a constant column (indexes:" + constantColumnIndexes + ") nor an explode map column (keyIndex:" + explodeMapKeyIndex + ", valueIndex:" + explodeMapValueIndex + ")");
                    }
                    String targetColumnType = targetColumnNames.get(targetIndex);

                    if (targetColumnType.toUpperCase().contains("TTL")
                            || targetColumnType.toUpperCase().contains("WRITETIME")) {
                        return; // there will be minor changes in TTL as read time of both
                                // differ significantly
                    }
                    if (logDebug)
                        logger.debug("Diff PK {}, target/origin index: {}/{} target/origin column: {}/{} target/origin value: {}/{}", pk, targetIndex, originIndex, targetColumnNames.get(targetIndex), originIndex < 0 ? "null" : originSession.getCqlTable().getColumnNames(false).get(originIndex), targetAsOriginType, origin);
                    if (null != origin &&
                            DataUtility.diff(origin, targetAsOriginType)) {
                        logger.info("Datatype is: {}",targetColumnType);
                        String originContent = CqlData.getFormattedContent(CqlData.toType(originColumnTypes.get(originIndex)), origin);
                        String targetContent = CqlData.getFormattedContent(CqlData.toType(targetColumnTypes.get(targetIndex)), targetAsOriginType);
                        diffData.append("Target column:").append(targetColumnNames.get(targetIndex))
                                .append("-origin[").append(originContent).append("]")
                                .append("-target[").append(targetContent).append("]; ");
                    }
                } catch (Exception e) {
                    String exceptionName;
                    String myClassMethodLine = DataUtility.getMyClassMethodLine(e);
                    if (e instanceof ArrayIndexOutOfBoundsException) {
                        exceptionName = "ArrayIndexOutOfBoundsException@" + myClassMethodLine;
                    } else {
                        exceptionName = e + "@" + myClassMethodLine;
                    }
                    diffData.append("Target column:").append(targetColumnNames.get(targetIndex)).append(" Exception ").append(exceptionName).append(" targetIndex:").append(targetIndex).append(" originIndex:").append(originIndex).append("; ");
                }
            } finally {
                ThreadContext.put(THREAD_CONTEXT_LABEL, previousLabel);
            }
        });
        return diffData.toString();
    }

}
