package de.tum.viewmaintenance.trigger;

import com.datastax.driver.core.Row;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.google.gson.internal.LinkedTreeMap;
import de.tum.viewmaintenance.client.CassandraClientUtilities;
import de.tum.viewmaintenance.config.ViewMaintenanceUtilities;
import de.tum.viewmaintenance.view_table_structure.Column;
import de.tum.viewmaintenance.view_table_structure.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketException;
import java.util.*;

/**
 * Created by shazra on 7/12/15.
 */
public class ReverseJoinViewTrigger extends TriggerProcess {
    // vt5 -> This view performs "reverseJoinView"
    private static final Logger logger = LoggerFactory.getLogger(ReverseJoinViewTrigger.class);
    @Override
    public TriggerResponse insertTrigger(TriggerRequest request) {
        logger.debug("**********Insert Trigger for reverse join view maintenance**********");
        TriggerResponse triggerResponse = new TriggerResponse();
        LinkedTreeMap dataMap = request.getDataJson();
        boolean isResultSuccessful = false;
        Table table = request.getViewTable();
        logger.debug("printing table = " + table);
        List<Column> columns = table.getColumns();
        Set keySet = dataMap.keySet();
        Iterator dataIter = keySet.iterator();
        String tempUserId = "";
//        int age = 0;
        String colAggKey = "";
        List<String> colValues = new ArrayList<>();
        while (dataIter.hasNext()) {
            String tempDataKey = (String) dataIter.next();
            logger.debug("Key: " + tempDataKey);
            logger.debug("Value: " + dataMap.get(tempDataKey));

            if (tempDataKey.equals("user_id")) {
                tempUserId = (String) dataMap.get(tempDataKey);
//            } else if (tempDataKey.equals("age")) {
//                age = Integer.parseInt((String) dataMap.get(tempDataKey));
            }  else if (tempDataKey.equals("colaggkey_x")) {
                colAggKey = (String) dataMap.get(tempDataKey);
                colAggKey = colAggKey.replaceAll("'","");
            } else {
                // Getting all the other columns of the table
                colValues.add((String) dataMap.get(tempDataKey));
            }
        }

        Statement existingRecordReverseJoinViewQuery = QueryBuilder
                .select(request.getBaseTableKeySpace() + "_" + request.getBaseTableName())
                .from(request.getViewKeyspace(), request.getViewTable().getName())
                .where(QueryBuilder.eq("colaggkey_x", colAggKey));

        List<Row> existingRecordReverseJoinView  = null;
        try {
            existingRecordReverseJoinView = CassandraClientUtilities.commandExecution(
                    CassandraClientUtilities.getEth0Ip(), existingRecordReverseJoinViewQuery);
        } catch ( SocketException e ) {
            logger.debug("Error !!" + ViewMaintenanceUtilities.getStackTrace(e));
        }

        logger.debug("Reverse join | insertTrigger | existing record: " + existingRecordReverseJoinView);

        // Check in the delta view if the primary key had previous occurrence with a different colAggKey_x
        String prevAggKey = previousAggKeyIfOld(request.getBaseTableName() + DeltaViewTrigger.DELTAVIEW_SUFFIX, request.getBaseTableKeySpace(), tempUserId);

        if (!"".equalsIgnoreCase(prevAggKey)) {
            if(!deleteInReverseJoinViewTable(request, tempUserId, prevAggKey)) {
                return triggerResponse;
            }
        }

        if (existingRecordReverseJoinView.size() == 0) {
            // Insert into the reverse join view
            triggerResponse.setIsSuccess(insertIntoReverseJoinViewTable(request, tempUserId, colAggKey, colValues));
        } else {
            // Update the reverse join view
            triggerResponse.setIsSuccess(updateIntoReverseJoinViewTable(request, tempUserId, colAggKey, colValues, existingRecordReverseJoinView.get(0)));
        }

        return triggerResponse;
    }



    @Override
    public TriggerResponse updateTrigger(TriggerRequest request) {
        return null;
    }

    /*
    *
    * This method handles the delete trigger for reverse join view maintenance
    *
    */
    @Override
    public TriggerResponse deleteTrigger(TriggerRequest request) {
        logger.debug("**********Delete Trigger for reverse join view maintenance**********");
        TriggerResponse triggerResponse = new TriggerResponse();
        boolean isResultSuccessful = false;
        Table viewTable = request.getViewTable();
        logger.debug("printing table = " + viewTable);
        List<Column> columns = viewTable.getColumns();
        Row rowDeletedDeltaView = request.getDeletedRowDeltaView();
        logger.info("Row needs to be deleted = {}", rowDeletedDeltaView);
        String tempUserId = rowDeletedDeltaView.getInt("user_id") + "";
        String colAggKey_cur = rowDeletedDeltaView.getString("colaggkey_x_cur");
        int age = rowDeletedDeltaView.getInt("age_cur");
        triggerResponse.setIsSuccess(deleteInReverseJoinViewTable(request, tempUserId, colAggKey_cur));
        return triggerResponse;
    }


    /*
    *
    * This method inserts a row in the reverse join view specialised for age column only.
    *
    */
    private boolean insertIntoReverseJoinViewTable(TriggerRequest request, String primaryKeyBaseTable, String colAggKey, int age){
        logger.debug("********** insertIntoReverseJoinViewTable **********");
        boolean isResultSucc = false;
        try {
            Table viewTable = request.getViewTable();
            Map<Integer, String> mapViewTable = new HashMap<>();
            mapViewTable.put(Integer.parseInt(primaryKeyBaseTable), age + "");
            Statement insertQueryStatement = QueryBuilder.insertInto(request.getViewKeyspace(), request.getViewTable().getName())
                    .value(viewTable.getBasedOn(), colAggKey).value( request.getBaseTableKeySpace() + "_" +
                            request.getBaseTableName(), mapViewTable);
            logger.debug("insertIntoReverseJoinViewTable Query {} ", insertQueryStatement);
            CassandraClientUtilities.commandExecution(CassandraClientUtilities.getEth0Ip(), insertQueryStatement);
            isResultSucc = true;
        } catch (Exception e) {
            logger.debug("Error !!" + ViewMaintenanceUtilities.getStackTrace(e));
            isResultSucc = false;
        }
        return  isResultSucc;
    }


    /*
    *
    * This method inserts a row in the reverse join view generic for all the columns passed in the list.
    *
    */
    private boolean insertIntoReverseJoinViewTable(TriggerRequest request, String primaryKeyBaseTable,
                                                   String colAggKey, List<String> colValues){
        logger.debug("********** insertIntoReverseJoinViewTable **********");
        boolean isResultSucc = false;
        try {
            Table viewTable = request.getViewTable();
            Map<Integer, String> mapViewTable = new HashMap<>();
            StringBuffer finalValue = new StringBuffer();
            // Framing a single String which needs to be mapped with the corresponding primary key in the reverse join view.
            for (String tempStr: colValues) {
                finalValue.append(tempStr + ",");
            }

            mapViewTable.put(Integer.parseInt(primaryKeyBaseTable), finalValue.toString());
            Statement insertQueryStatement = QueryBuilder.insertInto(request.getViewKeyspace(), request.getViewTable().getName())
                    .value(viewTable.getBasedOn(), colAggKey).value( request.getBaseTableKeySpace() + "_" +
                            request.getBaseTableName(), mapViewTable);
            logger.debug("insertIntoReverseJoinViewTable Query {} ", insertQueryStatement);
            CassandraClientUtilities.commandExecution(CassandraClientUtilities.getEth0Ip(), insertQueryStatement);
            isResultSucc = true;
        } catch (Exception e) {
            logger.debug("Error !!" + ViewMaintenanceUtilities.getStackTrace(e));
            isResultSucc = false;
        }
        return  isResultSucc;
    }

    /*
    * This method adds a cell in the list of the reverse join view specialised for age column.
    *
    */
    private boolean updateIntoReverseJoinViewTable(TriggerRequest request, String primaryKeyBaseTable, String colAggKey, int age, Row existingRecord){
        logger.debug("********** updateIntoReverseJoinViewTable **********");
        boolean isResultSucc = false;
        boolean isPrimaryKeyNew = true;
        try {
            String colNameInViewTable = request.getBaseTableKeySpace() + "_" + request.getBaseTableName();
            Map<Integer,String> existingRecordMap = existingRecord.getMap(colNameInViewTable, Integer.class, String.class);
            logger.debug(" updateIntoReverseJoinViewTable | existingRecordMap : " + existingRecordMap);
            // Updating the map with the new value
            Map<Integer, String> finalRecordMap = new HashMap<>();
            for (Map.Entry<Integer, String> entry : existingRecordMap.entrySet()) {
                if (entry.getKey() == Integer.parseInt(primaryKeyBaseTable)) {
                    finalRecordMap.put(entry.getKey(), age + "");
                    isPrimaryKeyNew = false;
                }
                else {
                    finalRecordMap.put(entry.getKey(), entry.getValue());
                }
            }

            if (isPrimaryKeyNew) {
                finalRecordMap.put(Integer.parseInt(primaryKeyBaseTable), age + "");
            }

            Statement updateStatement = QueryBuilder.update(request.getViewKeyspace(), request.getViewTable().getName())
                    .with(QueryBuilder.set(colNameInViewTable, finalRecordMap))
                    .where(QueryBuilder.eq(request.getViewTable().getBasedOn(), colAggKey));
            logger.debug("Update Reverse join view using query : " + updateStatement);
            CassandraClientUtilities.commandExecution(CassandraClientUtilities.getEth0Ip(), updateStatement);
            isResultSucc = true;
        } catch (Exception e) {
            logger.debug("Error !!" + ViewMaintenanceUtilities.getStackTrace(e));
            isResultSucc = false;
        }
        return  isResultSucc;
    }


    /*
    * This method adds a cell in the list of the reverse join view generic for the column values in the list.
    *
    */
    private boolean updateIntoReverseJoinViewTable(TriggerRequest request, String primaryKeyBaseTable,
                                                   String colAggKey, List<String> colValues, Row existingRecord){
        logger.debug("********** updateIntoReverseJoinViewTable **********");
        boolean isResultSucc = false;
        boolean isPrimaryKeyNew = true;
        try {
            String colNameInViewTable = request.getBaseTableKeySpace() + "_" + request.getBaseTableName();
            Map<Integer,String> existingRecordMap = existingRecord.getMap(colNameInViewTable, Integer.class, String.class);
            logger.debug(" updateIntoReverseJoinViewTable | existingRecordMap : " + existingRecordMap);

            StringBuffer finalColValue = new StringBuffer();
            // Framing the new value with list
            for (String tempStr : colValues) {
                finalColValue.append(tempStr + ",");
            }

            // Updating the map with the new value
            Map<Integer, String> finalRecordMap = new HashMap<>();
            for (Map.Entry<Integer, String> entry : existingRecordMap.entrySet()) {
                if (entry.getKey() == Integer.parseInt(primaryKeyBaseTable)) {
                    finalRecordMap.put(entry.getKey(),  finalColValue.toString());
                    isPrimaryKeyNew = false;
                }
                else {
                    finalRecordMap.put(entry.getKey(), entry.getValue());
                }
            }

            if (isPrimaryKeyNew) {
                finalRecordMap.put(Integer.parseInt(primaryKeyBaseTable), finalColValue.toString());
            }

            Statement updateStatement = QueryBuilder.update(request.getViewKeyspace(), request.getViewTable().getName())
                    .with(QueryBuilder.set(colNameInViewTable, finalRecordMap))
                    .where(QueryBuilder.eq(request.getViewTable().getBasedOn(), colAggKey));
            logger.debug("Update Reverse join view using query : " + updateStatement);
            CassandraClientUtilities.commandExecution(CassandraClientUtilities.getEth0Ip(), updateStatement);
            isResultSucc = true;
        } catch (Exception e) {
            logger.debug("Error !!" + ViewMaintenanceUtilities.getStackTrace(e));
            isResultSucc = false;
        }
        return  isResultSucc;
    }

    /**
    * This method deletes cell from the list of the reverse join view.
    **/

    private boolean deleteInReverseJoinViewTable(TriggerRequest request, String primaryKeyBaseTable, String colAggKey){
        boolean isDeleteSucc = false;
        logger.debug("********** deleteInReverseJoinViewTable **********");
        try {
            String colNameInViewTable = request.getBaseTableKeySpace() + "_" + request.getBaseTableName();
            Statement selectPreAggregationOldColAggKey = QueryBuilder.select()
                    .from(request.getViewKeyspace(), request.getViewTable().getName())
                    .where(QueryBuilder.eq(request.getViewTable().getBasedOn(), colAggKey));
            Row existingRecord = CassandraClientUtilities.commandExecution(CassandraClientUtilities.getEth0Ip(), selectPreAggregationOldColAggKey).get(0);
            Map<Integer, String> existingRecordStr = existingRecord.getMap(colNameInViewTable, Integer.class, String.class);

            Map<Integer, String> finalRecordMap = new HashMap<>();
            for (Map.Entry<Integer, String> entry : existingRecordStr.entrySet()) {
                if (entry.getKey() != Integer.parseInt(primaryKeyBaseTable)) {
                    finalRecordMap.put(entry.getKey(), entry.getValue());
                }
            }

            Statement updateStatement = QueryBuilder.update(request.getViewKeyspace(), request.getViewTable().getName())
                    .with(QueryBuilder.set(colNameInViewTable, finalRecordMap)).
                            where(QueryBuilder.eq(request.getViewTable().getBasedOn(), colAggKey));
            logger.debug("Update(delete) reverse join view using query : " + updateStatement);
            CassandraClientUtilities.commandExecution(CassandraClientUtilities.getEth0Ip(), updateStatement);
            isDeleteSucc = true;
        } catch (Exception e) {
            logger.debug("Error !!" + ViewMaintenanceUtilities.getStackTrace(e));
            isDeleteSucc = false;
        }
        return isDeleteSucc;
    }


    private String previousAggKeyIfOld(String deltaTableName, String deltaTableKeyspace, String primaryKeyBaseTable){
        logger.debug("********** previousAggKeyIfOld Check **********");
        Statement selectDeltaViewQuery = QueryBuilder.select().from(deltaTableKeyspace, deltaTableName)
                .where(QueryBuilder.eq("user_id", Integer.parseInt(primaryKeyBaseTable)));
        logger.debug("previousAggKeyIfOld | selectDeltaViewQuery | " + selectDeltaViewQuery);
        Row record = null;
        try {
            record = CassandraClientUtilities.commandExecution(CassandraClientUtilities.getEth0Ip(), selectDeltaViewQuery).get(0);
        } catch ( SocketException e ) {
            logger.debug("Error !!" + ViewMaintenanceUtilities.getStackTrace(e));
        }
        logger.debug("previousAggKeyIfOld | record | " + record);
        String colAggKeyValue_last = record.getString("colaggkey_x_last");
        String colAggKeyValue_cur = record.getString("colaggkey_x_cur");
        logger.debug("colAggKeyValue_last = {}", colAggKeyValue_last);
        logger.debug("colAggKeyValue_cur = {} ", colAggKeyValue_cur);
        if ( colAggKeyValue_cur != null && colAggKeyValue_last != null && !colAggKeyValue_cur.equalsIgnoreCase(colAggKeyValue_last)) {
            return colAggKeyValue_last;
        } else {
            return "";
        }
    }
}
