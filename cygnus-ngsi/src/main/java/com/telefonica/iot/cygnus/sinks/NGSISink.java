/**
 * Copyright 2016 Telefonica Investigación y Desarrollo, S.A.U
 *
 * This file is part of fiware-cygnus (FI-WARE project).
 *
 * fiware-cygnus is free software: you can redistribute it and/or modify it under the terms of the GNU Affero
 * General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 * fiware-cygnus is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the
 * implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with fiware-cygnus. If not, see
 * http://www.gnu.org/licenses/.
 *
 * For those usages not covered by the GNU Affero General Public License please contact with iot_support at tid dot es
 */

package com.telefonica.iot.cygnus.sinks;

import com.telefonica.iot.cygnus.containers.NotifyContextRequest.ContextAttribute;
import com.telefonica.iot.cygnus.containers.NotifyContextRequest.ContextElement;
import com.telefonica.iot.cygnus.errors.CygnusBadConfiguration;
import com.telefonica.iot.cygnus.errors.CygnusBadContextData;
import com.telefonica.iot.cygnus.errors.CygnusPersistenceError;
import com.telefonica.iot.cygnus.errors.CygnusRuntimeError;
import com.telefonica.iot.cygnus.interceptors.NGSIEvent;
import com.telefonica.iot.cygnus.log.CygnusLogger;
import com.telefonica.iot.cygnus.sinks.Enums.DataModel;
import static com.telefonica.iot.cygnus.sinks.Enums.DataModel.DMBYATTRIBUTE;
import static com.telefonica.iot.cygnus.sinks.Enums.DataModel.DMBYENTITY;
import static com.telefonica.iot.cygnus.sinks.Enums.DataModel.DMBYSERVICE;
import static com.telefonica.iot.cygnus.sinks.Enums.DataModel.DMBYSERVICEPATH;
import java.util.Map;
import com.telefonica.iot.cygnus.utils.CommonConstants;
import com.telefonica.iot.cygnus.utils.NGSIConstants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.Sink.Status;
import org.apache.flume.Transaction;
import org.apache.flume.conf.Configurable;
import org.apache.log4j.MDC;

/**
 *
 * @author frb

 Abstract class containing the common code to all the sinks persisting data comming from Orion Context Broker.

 The common attributes are:
  - there is no common attributes
 The common methods are:
  - void stop()
  - Status process() throws EventDeliveryException
  - void persistOne(Event getRecvTimeTs) throws Exception
 The non common parts, and therefore those that are sink dependant and must be implemented are:
  - void configure(Context context)
  - void start()
  - void persistOne(Map<String, String> eventHeaders, NotifyContextRequest notification) throws Exception
 */
public abstract class NGSISink extends CygnusSink implements Configurable {

    // logger
    private static final CygnusLogger LOGGER = new CygnusLogger(NGSISink.class);
    // general parameters for all the sinks
    protected DataModel dataModel;
    protected boolean enableGrouping;
    protected int batchSize;
    protected int batchTimeout;
    protected int batchTTL;
    protected int[] batchRetryIntervals;
    protected boolean enableLowercase;
    protected boolean invalidConfiguration;
    protected boolean enableEncoding;
    protected boolean enableNameMappings;
    // accumulator utility
    private final Accumulator accumulator;
    // rollback queues
    private ArrayList<Accumulator> rollbackedAccumulations;

    /**
     * Constructor.
     */
    public NGSISink() {
        super();

        // configuration is supposed to be valid
        invalidConfiguration = false;

        // create the accumulator utility
        accumulator = new Accumulator();

        // crete the rollbacking queue
        rollbackedAccumulations = new ArrayList<>();
    } // NGSISink
    
    protected String getBatchRetryIntervals() {
        return Arrays.toString(batchRetryIntervals).replaceAll("\\[", "").replaceAll("\\]", "");
    } // getBatchRetryIntervals
    
    /**
     * Gets the batch size.
     * @return The batch size.
     */
    protected int getBatchSize() {
        return batchSize;
    } // getBatchSize
    
    /**
     * Gets the batch timeout.
     * @return The batch timeout.
     */
    protected int getBatchTimeout() {
        return batchTimeout;
    } // getBatchTimeout
    
    /**
     * Gets the batch TTL.
     * @return The batch TTL.
     */
    protected int getBatchTTL() {
        return batchTTL;
    } // getBatchTTL
    
    /**
     * Gets the data model.
     * @return The data model
     */
    protected DataModel getDataModel() {
        return dataModel;
    } // getDataModel

    /**
     * Gets if the grouping feature is enabled.
     * @return True if the grouping feature is enabled, false otherwise.
     */
    protected boolean getEnableGrouping() {
        return enableGrouping;
    } // getEnableGrouping

    /**
     * Gets if lower case is enabled.
     * @return True is lower case is enabled, false otherwise.
     */
    protected boolean getEnableLowerCase() {
        return enableLowercase;
    } // getEnableLowerCase
    
    /**
     * Gets if the encoding is enabled.
     * @return True is the encoding is enabled, false otherwise.
     */
    protected boolean getEnableEncoding() {
        return enableEncoding;
    } // getEnableEncoding
    
    protected boolean getEnableNameMappings() {
        return enableNameMappings;
    } // getEnableNameMappings
    
    /**
     * Gets true if the configuration is invalid, false otherwise. It is protected due to it is only
     * required for testing purposes.
     * @return
     */
    protected boolean getInvalidConfiguration() {
        return invalidConfiguration;
    } // getInvalidConfiguration
    
    protected ArrayList<Accumulator> getRollbackedAccumulations() {
        return rollbackedAccumulations;
    } // getRollbackedAccumulations
    
    protected void setRollbackedAccumulations(ArrayList<Accumulator> rollbackedAccumulations) {
        this.rollbackedAccumulations = rollbackedAccumulations;
    } // setRollbackedAccumulations

    @Override
    public void configure(Context context) {
        String dataModelStr = context.getString("data_model", "dm-by-entity");

        try {
            dataModel = DataModel.valueOf(dataModelStr.replaceAll("-", "").toUpperCase());
            LOGGER.debug("[" + this.getName() + "] Reading configuration (data_model="
                    + dataModelStr + ")");
        } catch (Exception e) {
            invalidConfiguration = true;
            LOGGER.debug("[" + this.getName() + "] Invalid configuration (data_model="
                    + dataModelStr + ")");
        } // catch

        String enableGroupingStr = context.getString("enable_grouping", "false");
        
        if (enableGroupingStr.equals("true") || enableGroupingStr.equals("false")) {
            enableGrouping = Boolean.valueOf(enableGroupingStr);
            LOGGER.debug("[" + this.getName() + "] Reading configuration (enable_grouping="
                + enableGroupingStr + ")");
        }  else {
            invalidConfiguration = true;
            LOGGER.debug("[" + this.getName() + "] Invalid configuration (enable_grouping="
                + enableGroupingStr + ") -- Must be 'true' or 'false'");
        }  // if else
        
        String enableLowercaseStr = context.getString("enable_lowercase", "false");
        
        if (enableLowercaseStr.equals("true") || enableLowercaseStr.equals("false")) {
            enableLowercase = Boolean.valueOf(enableLowercaseStr);
            LOGGER.debug("[" + this.getName() + "] Reading configuration (enable_lowercase="
                + enableLowercaseStr + ")");
        }  else {
            invalidConfiguration = true;
            LOGGER.debug("[" + this.getName() + "] Invalid configuration (enable_lowercase="
                + enableLowercaseStr + ") -- Must be 'true' or 'false'");
        }  // if else

        batchSize = context.getInteger("batch_size", 1);

        if (batchSize <= 0) {
            invalidConfiguration = true;
            LOGGER.debug("[" + this.getName() + "] Invalid configuration (batch_size="
                    + batchSize + ") -- Must be greater than 0");
        } else {
            LOGGER.debug("[" + this.getName() + "] Reading configuration (batch_size="
                    + batchSize + ")");
        } // if else

        batchTimeout = context.getInteger("batch_timeout", 30);

        if (batchTimeout <= 0) {
            invalidConfiguration = true;
            LOGGER.debug("[" + this.getName() + "] Invalid configuration (batch_timeout="
                    + batchTimeout + ") -- Must be greater than 0");
        } else {
            LOGGER.debug("[" + this.getName() + "] Reading configuration (batch_timeout="
                    + batchTimeout + ")");
        } // if

        batchTTL = context.getInteger("batch_ttl", 10);
        
        if (batchTTL < -1) {
            invalidConfiguration = true;
            LOGGER.debug("[" + this.getName() + "] Invalid configuration (batch_ttl="
                    + batchTTL + ") -- Must be greater than -2");
        } else {
            LOGGER.debug("[" + this.getName() + "] Reading configuration (batch_ttl="
                    + batchTTL + ")");
        } // if else
        
        String enableEncodingStr = context.getString("enable_encoding", "false");
        
        if (enableEncodingStr.equals("true") || enableEncodingStr.equals("false")) {
            enableEncoding = Boolean.valueOf(enableEncodingStr);
            LOGGER.debug("[" + this.getName() + "] Reading configuration (enable_encoding="
                + enableEncodingStr + ")");
        }  else {
            invalidConfiguration = true;
            LOGGER.debug("[" + this.getName() + "] Invalid configuration (enable_encoding="
                + enableEncodingStr + ") -- Must be 'true' or 'false'");
        }  // if else
        
        String enableNameMappingsStr = context.getString("enable_name_mappings", "false");
        
        if (enableNameMappingsStr.equals("true") || enableNameMappingsStr.equals("false")) {
            enableNameMappings = Boolean.valueOf(enableNameMappingsStr);
            LOGGER.debug("[" + this.getName() + "] Reading configuration (enable_name_mappings="
                + enableNameMappingsStr + ")");
        }  else {
            invalidConfiguration = true;
            LOGGER.debug("[" + this.getName() + "] Invalid configuration (enable_name_mappings="
                + enableNameMappingsStr + ") -- Must be 'true' or 'false'");
        }  // if else
        
        String batchRetryIntervalsStr = context.getString("batch_retry_intervals", "5000");
        String[] batchRetryIntervalsSplit = batchRetryIntervalsStr.split(",");
        batchRetryIntervals = new int[batchRetryIntervalsSplit.length];
        boolean allOK = true;
        
        for (int i = 0; i < batchRetryIntervalsSplit.length; i++) {
            String batchRetryIntervalStr = batchRetryIntervalsSplit[i];
            int batchRetryInterval = new Integer(batchRetryIntervalStr);
            
            if (batchRetryInterval <= 0) {
                invalidConfiguration = true;
                LOGGER.debug("[" + this.getName() + "] Invalid configuration (batch_retry_intervals="
                        + batchRetryIntervalStr + ") -- Members must be greater than 0");
                allOK = false;
                break;
            } else {
                batchRetryIntervals[i] = batchRetryInterval;
            } // if else
        } // for
        
        if (allOK) {
            LOGGER.debug("[" + this.getName() + "] Reading configuration (batch_retry_intervals="
                    + batchRetryIntervalsStr + ")");
        } // if
    } // configure

    @Override
    public void start() {
        super.start();

        if (invalidConfiguration) {
            LOGGER.info("[" + this.getName() + "] Startup completed. Nevertheless, there are errors "
                    + "in the configuration, thus this sink will not run the expected logic");
        } else {
            // the accumulator must be initialized once read the configuration
            accumulator.initialize(new Date().getTime());
            LOGGER.info("[" + this.getName() + "] Startup completed");
        } // if else
    } // start

    @Override
    public void stop() {
        super.stop();
    } // stop

    @Override
    public Status process() throws EventDeliveryException {
        if (invalidConfiguration) {
            return Status.BACKOFF;
        } else if (rollbackedAccumulations.isEmpty()) {
            return processNewBatches();
        } else {
            processRollbackedBatches();
            return processNewBatches();
        } // if else
    } // process

    private Status processRollbackedBatches() throws EventDeliveryException {
        // Get a rollbacked accumulation
        Accumulator rollbackedAccumulation = getRollbackedAccumulationForRetry();

        if (rollbackedAccumulation == null) {
            return Status.READY; // No rollbacked batch was ready for retry, so we are ready to process new batches
        } // if
            
        // Try persisting the rollbacked accumulation
        try {
            persistBatch(rollbackedAccumulation.getBatch());
            
            if (!rollbackedAccumulation.getAccTransactionIds().isEmpty()) {
                LOGGER.info("Finishing internal transaction (" + rollbackedAccumulation.getAccTransactionIds() + ")");
            } // if
            
            rollbackedAccumulations.remove(0);
            numPersistedEvents += rollbackedAccumulation.getBatch().getNumEvents();
            return Status.READY;
        } catch (Exception e) {
            LOGGER.debug(Arrays.toString(e.getStackTrace()));

            // Rollback only if the exception is about a persistence error
            if (e instanceof CygnusPersistenceError) {
                LOGGER.error(e.getMessage());
                doRollbackAgain(rollbackedAccumulation);
                return Status.BACKOFF; // Slow down the sink since there are problems with the persistence backend
            } else {
                if (e instanceof CygnusRuntimeError) {
                    LOGGER.error(e.getMessage());
                } else if (e instanceof CygnusBadConfiguration) {
                    LOGGER.warn(e.getMessage());
                } else if (e instanceof CygnusBadContextData) {
                    LOGGER.warn(e.getMessage());
                } else {
                    LOGGER.warn(e.getMessage());
                } // if else if

                return Status.READY;
            } // if else
        } // try catch
    } // processRollbackedBatches
    
    /**
     * Gets a rollbacked accumulation for retry.
     * @return A rollbacked accumulation for retry.
     */
    protected Accumulator getRollbackedAccumulationForRetry() {
        Accumulator rollbackedAccumulation = null;
        
        for (Accumulator rollbackedAcc : rollbackedAccumulations) {
            rollbackedAccumulation = rollbackedAcc;
            
            // Check the last retry
            int retryIntervalIndex = batchTTL - rollbackedAccumulation.ttl;
            
            if (retryIntervalIndex >= batchRetryIntervals.length) {
                retryIntervalIndex = batchRetryIntervals.length - 1;
            } // if
            
            if (rollbackedAccumulation.getLastRetry() + batchRetryIntervals[retryIntervalIndex]
                    <= new Date().getTime()) {
                break;
            } // if
            
            rollbackedAccumulation = null;
        } // for
        
        return rollbackedAccumulation;
    } // getRollbackedAccumulationForRetry
    
    /**
     * Rollbacks the accumulation once more.
     * @param rollbackedAccumulation
     */
    protected void doRollbackAgain(Accumulator rollbackedAccumulation) {
        if (rollbackedAccumulation.getTTL() == -1) {
            rollbackedAccumulation.setLastRetry(new Date().getTime());
            LOGGER.info("Rollbacking again (" + rollbackedAccumulation.getAccTransactionIds() + "), "
                    + "infinite batch TTL");
        } else if (rollbackedAccumulation.getTTL() > 1) {
            rollbackedAccumulation.setLastRetry(new Date().getTime());
            rollbackedAccumulation.setTTL(rollbackedAccumulation.getTTL() - 1);
            LOGGER.info("Rollbacking again (" + rollbackedAccumulation.getAccTransactionIds() + "), "
                    + "this was retry #" + (batchTTL - rollbackedAccumulation.getTTL()));
        } else {
            rollbackedAccumulations.remove(rollbackedAccumulation);

            if (!rollbackedAccumulation.getAccTransactionIds().isEmpty()) {
                LOGGER.info("Finishing internal transaction ("
                        + rollbackedAccumulation.getAccTransactionIds() + "), this was retry #" + batchTTL);
            } // if
        } // if else
    } // doRollbackAgain

    private Status processNewBatches() throws EventDeliveryException {
        // get the channel
        Channel ch = null;

        try {
            ch = getChannel();
        } catch (Exception e) {
            LOGGER.error("Channel error (The channel could not be got. Details=" + e.getMessage() + ")");
            throw new EventDeliveryException(e);
        } // try catch

        // start a Flume transaction (it is not the same than a Cygnus transaction!)
        Transaction txn = null;

        try {
            txn = ch.getTransaction();
            txn.begin();
        } catch (Exception e) {
            LOGGER.error("Channel error (The Flume transaction could not be started. Details=" + e.getMessage() + ")");
            throw new EventDeliveryException(e);
        } // try catch

        // get and process as many events as the batch size
        int currentIndex;

        for (currentIndex = accumulator.getAccIndex(); currentIndex < batchSize; currentIndex++) {
            // check if the batch accumulation timeout has been reached
            if ((new Date().getTime() - accumulator.getAccStartDate()) > (batchTimeout * 1000)) {
                LOGGER.debug("Batch accumulation time reached, the batch will be processed as it is");
                break;
            } // if

            // get an getRecvTimeTs
            NGSIEvent event = null;

            try {
                event = (NGSIEvent) ch.take();
            } catch (Exception e) {
                LOGGER.error("Channel error (The event could not be got. Details: " + e.getMessage() + ")");
                throw new EventDeliveryException(e);
            } // try catch

            // check if the getRecvTimeTs is null
            if (event == null) {
                accumulator.setAccIndex(currentIndex);
                txn.commit();
                txn.close();
                return Status.BACKOFF; // slow down the sink since no events are available
            } // if

            // set the correlation ID, transaction ID, service and service path in MDC
            try {
                MDC.put(CommonConstants.LOG4J_CORR,
                        event.getHeaders().get(CommonConstants.HEADER_CORRELATOR_ID));
                MDC.put(CommonConstants.LOG4J_TRANS,
                        event.getHeaders().get(NGSIConstants.FLUME_HEADER_TRANSACTION_ID));
                MDC.put(CommonConstants.LOG4J_SVC,
                        event.getHeaders().get(CommonConstants.HEADER_FIWARE_SERVICE));
                MDC.put(CommonConstants.LOG4J_SUBSVC,
                        event.getHeaders().get(CommonConstants.HEADER_FIWARE_SERVICE_PATH));
            } catch (Exception e) {
                LOGGER.error("Runtime error (" + e.getMessage() + ")");
            } // catch

            // Accumulate the getRecvTimeTs
            try {
                accumulator.accumulate(event);
                numProcessedEvents++;
            } catch (Exception e) {
                LOGGER.error("There was some problem when accumulating the notified context element. "
                        + "Details: " + e.getMessage());
            } // try catch
        } // for

        // save the current index for next run of the process() method
        accumulator.setAccIndex(currentIndex);

        // persist the accumulation
        try {
            if (accumulator.getAccIndex() != 0) {
                LOGGER.debug("Batch completed, persisting it");
                persistBatch(accumulator.getBatch());
            } // if

            if (!accumulator.getAccTransactionIds().isEmpty()) {
                LOGGER.info("Finishing internal transaction (" + accumulator.getAccTransactionIds() + ")");
            } // if
            
            numPersistedEvents += accumulator.getBatch().getNumEvents();
            accumulator.initialize(new Date().getTime());
            txn.commit();
            txn.close();
            return Status.READY;
        } catch (Exception e) {
            LOGGER.debug(Arrays.toString(e.getStackTrace()));

            // rollback only if the exception is about a persistence error
            if (e instanceof CygnusPersistenceError) {
                LOGGER.error(e.getMessage());
                doRollback(accumulator.clone()); // the global accumulator has to be cloned for rollbacking purposes
                accumulator.initialize(new Date().getTime());
                txn.commit();
                txn.close();
                return Status.BACKOFF; // slow down the sink since there are problems with the persistence backend
            } else {
                if (e instanceof CygnusRuntimeError) {
                    LOGGER.error(e.getMessage());
                } else if (e instanceof CygnusBadConfiguration) {
                    LOGGER.warn(e.getMessage());
                } else if (e instanceof CygnusBadContextData) {
                    LOGGER.warn(e.getMessage());
                } else {
                    LOGGER.warn(e.getMessage());
                } // if else if

                accumulator.initialize(new Date().getTime());
                txn.commit();
                txn.close();
                return Status.READY;
            } // if else
        } // try catch
    } // processNewBatches

    /**
     * Rollbacks the accumulator for the first time.
     * @param accumulator Accumulator to be rollbacked
     */
    protected void doRollback(Accumulator accumulator) {
        if (accumulator.getTTL() == -1) {
            accumulator.setLastRetry(new Date().getTime());
            rollbackedAccumulations.add(accumulator);
            LOGGER.info("Rollbacking (" + accumulator.getAccTransactionIds() + "), "
                    + "infinite batch TTL");
        } else if (accumulator.getTTL() > 0) {
            accumulator.setLastRetry(new Date().getTime());
            rollbackedAccumulations.add(accumulator);
            LOGGER.info("Rollbacking (" + accumulator.getAccTransactionIds() + "), "
                    + batchTTL + " retries will be done");
        } else {
            if (!accumulator.getAccTransactionIds().isEmpty()) {
                LOGGER.info("Finishing internal transaction ("
                        + accumulator.getAccTransactionIds() + "), 0 retries will be done");
            } // if
        } // if else
    } // doRollback

    /**
     * Utility class for batch-like getRecvTimeTs accumulation purposes.
     */
    protected class Accumulator implements Cloneable {

        // accumulated events
        private NGSIBatch batch;
        private long accStartDate;
        private int accIndex;
        private String accTransactionIds;
        private int ttl;
        private long lastRetry;

        /**
         * Constructor.
         */
        public Accumulator() {
            batch = new NGSIBatch();
            accStartDate = 0;
            accIndex = 0;
            accTransactionIds = null;
            ttl = batchTTL;
            lastRetry = 0;
        } // Accumulator

        public long getAccStartDate() {
            return accStartDate;
        } // getAccStartDate

        public int getAccIndex() {
            return accIndex;
        } // getAccIndex

        public void setAccIndex(int accIndex) {
            this.accIndex = accIndex;
        } // setAccIndex

        public NGSIBatch getBatch() {
            return batch;
        } // getBatch

        public String getAccTransactionIds() {
            return accTransactionIds;
        } // getAccTransactionIds
        
        public long getLastRetry() {
            return lastRetry;
        } // getLastRetry
        
        public void setLastRetry(long lastRetry) {
            this.lastRetry = lastRetry;
        } // setLastRetry
        
        public int getTTL() {
            return ttl;
        } // getTTL
        
        public void setTTL(int ttl) {
            this.ttl = ttl;
        } // setTTL

        /**
         * Accumulates an getRecvTimeTs given its headers and context data.
         * @param event
         */
        public void accumulate(NGSIEvent event) {
            String transactionId = event.getHeaders().get(CommonConstants.HEADER_CORRELATOR_ID);

            if (accTransactionIds.isEmpty()) {
                accTransactionIds = transactionId;
            } else {
                accTransactionIds += "," + transactionId;
            } // if else

            switch (dataModel) {
                case DMBYSERVICE:
                    accumulateByService(event);
                    break;
                case DMBYSERVICEPATH:
                    accumulateByServicePath(event);
                    break;
                case DMBYENTITY:
                    accumulateByEntity(event);
                    break;
                case DMBYATTRIBUTE:
                    accumulateByAttribute(event);
                    break;
                default:
                    LOGGER.error("Unknown data model. Details=" + dataModel.toString());
            } // switch
        } // accumulate

        private void accumulateByService(NGSIEvent event) {
            Map<String, String> headers = event.getHeaders();
            ContextElement mappedCE = event.getMappedCE();
            String destination;
            
            if (mappedCE == null) { // 'TODO': remove when Grouping Rules are definitely removed
                destination = headers.get(CommonConstants.HEADER_FIWARE_SERVICE);
            } else {
                if (enableNameMappings) {
                    destination = headers.get(NGSIConstants.FLUME_HEADER_MAPPED_SERVICE);
                } else {
                    destination = headers.get(CommonConstants.HEADER_FIWARE_SERVICE);
                } // if else
            } // if else
            
            batch.addEvent(destination, event);
        } // accumulateByService

        private void accumulateByServicePath(NGSIEvent event) {
            Map<String, String> headers = event.getHeaders();
            ContextElement mappedCE = event.getMappedCE();
            String destination;
            
            if (mappedCE == null) { // 'TODO': remove when Grouping Rules are definitely removed
                String service = headers.get(CommonConstants.HEADER_FIWARE_SERVICE);
                
                if (enableGrouping) {
                    destination = service + "_" + headers.get(NGSIConstants.FLUME_HEADER_GROUPED_SERVICE_PATH);
                } else {
                    destination = service + "_" + headers.get(CommonConstants.HEADER_FIWARE_SERVICE_PATH);
                } // if else
            } else {
                if (enableNameMappings) {
                    destination = headers.get(NGSIConstants.FLUME_HEADER_MAPPED_SERVICE) + "_"
                            + headers.get(NGSIConstants.FLUME_HEADER_MAPPED_SERVICE_PATH);
                } else {
                    destination = headers.get(CommonConstants.HEADER_FIWARE_SERVICE) + "_"
                            + headers.get(CommonConstants.HEADER_FIWARE_SERVICE_PATH);
                } // if else
            } // if else

            batch.addEvent(destination, event);
        } // accumulateByServicePath

        private void accumulateByEntity(NGSIEvent event) {
            Map<String, String> headers = event.getHeaders();
            ContextElement originalCE = event.getOriginalCE();
            ContextElement mappedCE = event.getMappedCE();
            String destination;
            
            if (mappedCE == null) { // 'TODO': remove when Grouping Rules are definitely removed
                String service = headers.get(CommonConstants.HEADER_FIWARE_SERVICE);
                
                if (enableGrouping) {
                    destination = service + "_" + headers.get(NGSIConstants.FLUME_HEADER_GROUPED_SERVICE_PATH)
                            + "_" + headers.get(NGSIConstants.FLUME_HEADER_GROUPED_ENTITY);
                } else {
                    destination = service + "_" + headers.get(CommonConstants.HEADER_FIWARE_SERVICE_PATH)
                            + "_" + headers.get(NGSIConstants.FLUME_HEADER_NOTIFIED_ENTITY);
                } // if else
            } else {
                if (enableNameMappings) {
                    destination = headers.get(NGSIConstants.FLUME_HEADER_MAPPED_SERVICE) + "_"
                            + headers.get(NGSIConstants.FLUME_HEADER_MAPPED_SERVICE_PATH) + "_"
                            + mappedCE.getId() + "_" + mappedCE.getType();
                } else {
                    destination = headers.get(CommonConstants.HEADER_FIWARE_SERVICE) + "_"
                            + headers.get(CommonConstants.HEADER_FIWARE_SERVICE_PATH) + "_"
                            + originalCE.getId() + "_" + originalCE.getType();
                } // if else
            } // if else

            batch.addEvent(destination, event);
        } // accumulateByEntity

        private void accumulateByAttribute(NGSIEvent event) {
            Map<String, String> headers = event.getHeaders();
            ContextElement originalCE = event.getOriginalCE();
            ContextElement mappedCE = event.getMappedCE();
            String destination;
            
            if (mappedCE == null) { // 'TODO': remove when Grouping Rules are definitely removed
                String service = headers.get(CommonConstants.HEADER_FIWARE_SERVICE);
                
                if (enableGrouping) {
                    destination = service + "_" + headers.get(NGSIConstants.FLUME_HEADER_GROUPED_SERVICE_PATH)
                            + "_" + headers.get(NGSIConstants.FLUME_HEADER_GROUPED_ENTITY);
                } else {
                    destination = service + "_" + headers.get(CommonConstants.HEADER_FIWARE_SERVICE_PATH)
                            + "_" + headers.get(NGSIConstants.FLUME_HEADER_NOTIFIED_ENTITY);
                } // if else
                
                ArrayList<ContextAttribute> attrs = originalCE.getAttributes();

                for (ContextAttribute attr : attrs) {
                    ContextElement filteredOriginalCE = originalCE.filter(attr.getName());
                    event.setOriginalCE(filteredOriginalCE);
                    batch.addEvent(destination + "_" + attr.getName(), event);
                } // for
            } else {
                if (enableNameMappings) {
                    destination = headers.get(NGSIConstants.FLUME_HEADER_MAPPED_SERVICE) + "_"
                            + headers.get(NGSIConstants.FLUME_HEADER_MAPPED_SERVICE_PATH) + "_"
                            + mappedCE.getId() + "_" + mappedCE.getType();
                    ArrayList<ContextAttribute> attrs = mappedCE.getAttributes();

                    for (ContextAttribute attr : attrs) {
                        ContextElement filteredOriginalCE = originalCE.filter(attr.getName());
                        ContextElement filteredMappedCE = mappedCE.filter(attr.getName());
                        event.setOriginalCE(filteredOriginalCE);
                        event.setMappedCE(filteredMappedCE);
                        batch.addEvent(destination + "_" + attr.getName(), event);
                    } // for
                } else {
                    destination = headers.get(CommonConstants.HEADER_FIWARE_SERVICE) + "_"
                            + headers.get(CommonConstants.HEADER_FIWARE_SERVICE_PATH) + "_"
                            + originalCE.getId() + "_" + originalCE.getType();
                    ArrayList<ContextAttribute> attrs = originalCE.getAttributes();

                    for (ContextAttribute attr : attrs) {
                        ContextElement filteredOriginalCE = originalCE.filter(attr.getName());
                        ContextElement filteredMappedCE = mappedCE.filter(attr.getName()); // not really necessary...
                        event.setOriginalCE(filteredOriginalCE);
                        event.setMappedCE(filteredMappedCE);
                        batch.addEvent(destination + "_" + attr.getName(), event);
                    } // for
                } // if else
            } // if else
        } // accumulateByAttribute

        /**
         * Initialize the batch.
         * @param startDateMs
         */
        public void initialize(long startDateMs) {
            // what happens if Cygnus falls down while accumulating the batch?
            // TBD: https://github.com/telefonicaid/fiware-cygnus/issues/562
            batch = new NGSIBatch();
            accStartDate = startDateMs;
            accIndex = 0;
            accTransactionIds = "";
            ttl = batchTTL;
        } // initialize

        @Override
        public Accumulator clone() {
            try {
                Accumulator acc = (Accumulator) super.clone();
                return acc;
            } catch (CloneNotSupportedException ce) {
                return null;
            } // clone
        } // clone

    } // Accumulator
    
    /**
     * This is the method the classes extending this class must implement when dealing with a batch of events to be
     * persisted.
     * @param batch
     * @throws Exception
     */
    abstract void persistBatch(NGSIBatch batch) throws Exception;

} // NGSISink
