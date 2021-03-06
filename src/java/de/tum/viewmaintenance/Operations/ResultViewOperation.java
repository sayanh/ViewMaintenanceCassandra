package de.tum.viewmaintenance.Operations;

import com.datastax.driver.core.Row;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Update;
import com.google.gson.internal.LinkedTreeMap;
import de.tum.viewmaintenance.client.CassandraClientUtilities;
import de.tum.viewmaintenance.config.PrimaryKey;
import de.tum.viewmaintenance.config.ViewMaintenanceUtilities;
import de.tum.viewmaintenance.trigger.DeltaViewTrigger;
import de.tum.viewmaintenance.trigger.TriggerRequest;
import de.tum.viewmaintenance.view_table_structure.Column;
import de.tum.viewmaintenance.view_table_structure.Table;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.db.filter.QueryFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketException;
import java.util.*;

/**
 * Created by shazra on 8/16/15.
 */
public class ResultViewOperation extends GenericOperation {

    private static final Logger logger = LoggerFactory.getLogger(ResultViewOperation.class);
    final static String WHERE_TABLE_INDENTIFIER = "_where_";
    final static String JOIN_TABLE_INDENTIFIER = "_innerjoin_";
    final static String AGG_TABLE_INDENTIFIER = "_agg";
    final static String PREAGG_TABLE_INDENTIFIER = "_preagg";
    private static final List<String> AVAILABLE_FUNCS = Arrays.asList("sum", "count", "min", "max");
    private Row deltaTableRecord;
    private List<Table> inputViewTables;
    private List<Table> operationViewTables;

    @Override
    public boolean insertTrigger(TriggerRequest triggerRequest) {
        logger.debug("##### Entering insert trigger for Final Result Operations!!! ");
        logger.debug("##### Received elements #####");
        logger.debug("##### Table structure involved: {}", this.operationViewTables);
        this.deltaTableRecord = triggerRequest.getCurrentRecordInDeltaView();
        logger.debug("##### Delta table record {}", this.deltaTableRecord);
        logger.debug("##### Input tables structure {}", this.inputViewTables);
        logger.debug("##### Trigger request :: " + triggerRequest);

        try {
            if ( inputViewTables.size() >= 1 && inputViewTables.get(0).getName().contains(WHERE_TABLE_INDENTIFIER) ) {
                logger.debug("#### Where insert trigger for result view maintenance!!");
                whereInsertTrigger(triggerRequest);
            } else if ( inputViewTables.size() == 1 && inputViewTables.get(0).getName().contains(JOIN_TABLE_INDENTIFIER) ) {
                logger.debug("#### Join insert trigger for result view maintenance!!");
            } else if ( inputViewTables.size() == 1 && inputViewTables.get(0).getName().contains(AGG_TABLE_INDENTIFIER) ) {
                logger.debug("#### Agg insert trigger for result view maintenance!!");
                aggInsertTrigger(triggerRequest);
            } else if ( inputViewTables.size() == 1 && inputViewTables.get(0).getName().contains(PREAGG_TABLE_INDENTIFIER) ) {
                logger.debug("#### Preagg insert trigger for result view maintenance!!");
                preaggInsertTrigger(triggerRequest);
            }
        } catch ( Exception e ) {
            logger.error("Error!!! " + ViewMaintenanceUtilities.getStackTrace(e));
            throw e;
        }

        return true;
    }


    private void aggInsertTrigger(TriggerRequest triggerRequest) {

        Table aggTable = inputViewTables.get(0);
        String functionName = "";
        String targetColName = "";
        String colAggKey = "";

        Map<String, ColumnDefinition> aggTableDesc = ViewMaintenanceUtilities.getTableDefinitition(aggTable.getKeySpace(),
                aggTable.getName());
        Map<String, List<String>> curUserData = new HashMap<>();
        // Column Name Mapped to -> internalCassandraType, value, isPrimaryKey


        PrimaryKey curColAggKeyPKResultView = null;
        PrimaryKey curColAggKeyPKAggView = null;
        Row existingCurRecordAggKeyAggView = null;
        LinkedTreeMap dataJson = triggerRequest.getDataJson();
        Map<String, List<String>> oldUserData = new HashMap<>();


        for ( Map.Entry<String, ColumnDefinition> aggViewColEntry : aggTableDesc.entrySet() ) {
            String derivedPrefix = aggViewColEntry.getKey().substring(0, aggViewColEntry.getKey()
                    .indexOf("_"));
            String derivedColumnName = aggViewColEntry.getKey().substring(aggViewColEntry.getKey()
                    .indexOf("_") + 1);

            if ( AVAILABLE_FUNCS.contains(derivedPrefix) ) {

                targetColName = derivedColumnName;
                functionName = derivedPrefix;

                List<String> tempResultViewColData = new ArrayList<>();
                //Format: internalCassandraType, value, isPrimaryKey

                tempResultViewColData.add(aggViewColEntry.getValue().type.toString());
                tempResultViewColData.add("");
                tempResultViewColData.add("false");
                curUserData.put(aggViewColEntry.getKey(), tempResultViewColData);
                oldUserData.put(aggViewColEntry.getKey(), tempResultViewColData);

            } else if ( aggViewColEntry.getValue().isPartitionKey() ) {

                curColAggKeyPKResultView = new PrimaryKey(derivedColumnName, aggViewColEntry.getValue().type.toString(),
                        ((String) dataJson.get(derivedColumnName)).replaceAll("'", ""));

                curColAggKeyPKAggView = new PrimaryKey(aggViewColEntry.getKey(), aggViewColEntry.getValue().type.toString(),
                        ((String) dataJson.get(derivedColumnName)).replaceAll("'", ""));

                colAggKey = derivedColumnName;

                try {
                    existingCurRecordAggKeyAggView = ViewMaintenanceUtilities.getExistingRecordIfExists(curColAggKeyPKAggView,
                            aggTable);
                } catch ( SocketException e ) {
                    logger.error("Error!!! " + ViewMaintenanceUtilities.getStackTrace(e));
                }

                logger.debug("#### existingRecordCurAggKeyAggView : " + existingCurRecordAggKeyAggView);

                List<String> tempResultViewColData = new ArrayList<>();
                //Format: internalCassandraType, value, isPrimaryKey

                tempResultViewColData.add(aggViewColEntry.getValue().type.toString());
                tempResultViewColData.add(((String) dataJson.get(derivedColumnName)).replaceAll("'", ""));
                tempResultViewColData.add("true");
                curUserData.put(derivedColumnName, tempResultViewColData);
                oldUserData.put(derivedColumnName, tempResultViewColData);
            }


        }

        logger.debug("#### Tentative user data for cur agg key(result view) : " + curUserData);

        Row existingCurRecordResultView = null;
        try {
            existingCurRecordResultView = ViewMaintenanceUtilities.getExistingRecordIfExists(curColAggKeyPKResultView,
                    operationViewTables.get(0));
        } catch ( SocketException e ) {
            logger.error("Error!!! " + ViewMaintenanceUtilities.getStackTrace(e));
        }


        logger.debug("#### curColAggKeyPKResultView : " + curColAggKeyPKResultView);

        if ( existingCurRecordAggKeyAggView != null ) {
            List<String> tempList = curUserData.get(functionName + "_" + targetColName);
            if ( ViewMaintenanceUtilities.getJavaTypeFromCassandraType(tempList.get(0)).equalsIgnoreCase("Integer") ) {
                tempList.set(1, existingCurRecordAggKeyAggView.getInt(functionName + "_" + targetColName) + "");
            } else if ( ViewMaintenanceUtilities.getJavaTypeFromCassandraType(tempList.get(0)).equalsIgnoreCase("String") ) {
                tempList.set(1, existingCurRecordAggKeyAggView.getString(functionName + "_" + targetColName));
            }

            curUserData.put(functionName + "_" + targetColName, tempList);

            logger.debug("#### Final user data for cur agg key(result view) : " + curUserData);


            aggActualInsertProcess(curUserData);

        } else {

            logger.debug("#### There is no record in the agg view for the cur agg key!!!!");

            if ( existingCurRecordResultView != null ) {
                deleteFromResultView(curColAggKeyPKResultView);
            }
        }


        List<String> aggregationKeyData = new ArrayList<>();
        aggregationKeyData.add(colAggKey); // Aggregation key column name
        aggregationKeyData.add(curUserData.get(curColAggKeyPKResultView.getColumnName()).get(0)); // Aggregation column type

        logger.debug("### Aggregation key data :: " + aggregationKeyData);
        String statusChangeInColAggKey = ViewMaintenanceUtilities.checkForChangeInAggregationKeyInDeltaView(aggregationKeyData,
                deltaTableRecord);

        if ( statusChangeInColAggKey.equalsIgnoreCase("changed") ) {

            PrimaryKey oldAggPKey = null;
            if ( curColAggKeyPKResultView.getColumnJavaType().equalsIgnoreCase("Integer") ) {
                oldAggPKey = new PrimaryKey(curColAggKeyPKAggView.getColumnName(),
                        curColAggKeyPKAggView.getColumnInternalCassType(),
                        deltaTableRecord.getInt(colAggKey + DeltaViewTrigger.LAST) + "");
            } else if ( curColAggKeyPKResultView.getColumnJavaType().equalsIgnoreCase("String") ) {
                oldAggPKey = new PrimaryKey(curColAggKeyPKAggView.getColumnName(),
                        curColAggKeyPKAggView.getColumnInternalCassType(),
                        deltaTableRecord.getString(colAggKey + DeltaViewTrigger.LAST));
            }

            PrimaryKey oldResultPKey = new PrimaryKey(colAggKey, oldAggPKey.getColumnInternalCassType(),
                    oldAggPKey.getColumnValueInString());

            logger.debug("#### Old primary key : " + oldAggPKey);

            Row existingOldRecordAggView = null;
            Row existingOldRecordResultView = null;
            try {
                existingOldRecordAggView = ViewMaintenanceUtilities.getExistingRecordIfExists(oldAggPKey,
                        aggTable);
                existingOldRecordResultView = ViewMaintenanceUtilities.getExistingRecordIfExists(oldResultPKey,
                        operationViewTables.get(0));
            } catch ( SocketException e ) {
                logger.error("Error!!! " + ViewMaintenanceUtilities.getStackTrace(e));
            }

            if ( existingOldRecordAggView != null ) {

                logger.debug("#### existingOldRecordAggView : " + existingOldRecordAggView);

                // Resetting the values for oldUserData
                for ( Map.Entry<String, List<String>> oldUserDataEntry : curUserData.entrySet() ) {
                    List<String> tempOldDataColList = oldUserDataEntry.getValue();
//                        if (oldUserDataEntry.getKey().equalsIgnoreCase(functionName + "_" + targetColName)) {
                    if ( ViewMaintenanceUtilities.getJavaTypeFromCassandraType(tempOldDataColList.get(0))
                            .equalsIgnoreCase("Integer") ) {

                        tempOldDataColList.set(1, existingOldRecordAggView.getInt(oldUserDataEntry.getKey()) + "");
                    } else if ( ViewMaintenanceUtilities.getJavaTypeFromCassandraType(tempOldDataColList.get(0))
                            .equalsIgnoreCase("String") ) {
                        tempOldDataColList.set(1, existingOldRecordAggView.getString(oldUserDataEntry.getKey()));
                    }
                    oldUserData.put(oldUserDataEntry.getKey(), tempOldDataColList);
//                        } else if (oldUserDataEntry.getKey().equalsIgnoreCase(colAggKey)) {
//                if ( ViewMaintenanceUtilities.getJavaTypeFromCassandraType(tempOldDataColList.get(0))
//                        .equalsIgnoreCase("Integer") ) {
//
//                    tempOldDataColList.set(1, existingOldRecordAggView.getInt(oldUserDataEntry.getKey()) + "");
//                } else if ( ViewMaintenanceUtilities.getJavaTypeFromCassandraType(tempOldDataColList.get(0))
//                        .equalsIgnoreCase("String") ) {
//                    tempOldDataColList.set(1, existingOldRecordAggView.getString(oldUserDataEntry.getKey()));
//                }
                    oldUserData.put(oldUserDataEntry.getKey(), tempOldDataColList);
//                        }
                }

                logger.debug("### oldUserData with values :: " + oldUserData);
                insertIntoResultView(oldUserData);
            } else {
                // The data for the old col agg key is not present in the agg view
                // So if it is there the in the result view then we gotta delete it

                if ( existingOldRecordResultView != null ) {
                    logger.debug("### Deleting old col agg key data from the result view!!! ");
                    deleteFromResultView(oldResultPKey);
                }
            }
        }
    }


    private void aggActualInsertProcess(Map<String, List<String>> userData) {

        List<String> colNames = new ArrayList<>();
        List<Object> objects = new ArrayList<>();

        for ( Map.Entry<String, List<String>> entry : userData.entrySet() ) {
            String javaDataType = ViewMaintenanceUtilities.getJavaTypeFromCassandraType(entry.getValue().get(0));
            colNames.add(entry.getKey());
            if ( javaDataType.equalsIgnoreCase("Integer") ) {
                objects.add(Integer.parseInt(entry.getValue().get(1)));
            } else if ( javaDataType.equalsIgnoreCase("String") ) {
                objects.add(entry.getValue().get(1));
            }
        }

        Statement insertIntoAggViewQuery = QueryBuilder.insertInto(operationViewTables.get(0).getKeySpace(),
                operationViewTables.get(0).getName()).values(colNames.toArray(new String[colNames.size()]),
                objects.toArray());

        logger.debug("### Insert query into Agg View :: " + insertIntoAggViewQuery);

        try {
            CassandraClientUtilities.commandExecution(CassandraClientUtilities.getEth0Ip(), insertIntoAggViewQuery);
        } catch ( SocketException e ) {
            logger.error("Error!!! " + ViewMaintenanceUtilities.getStackTrace(e));
        }

    }

    private void preaggInsertTrigger(TriggerRequest triggerRequest) {

        Table resultTable = operationViewTables.get(0);
        Table preaggTable = inputViewTables.get(0);
        List<String> aggregationKeyData = new ArrayList<>(); // Contains aggKeyColumnName, Cass Int Type, Value
        Map<String, List<String>> userDataCur = new HashMap<>(); // List format: Java datatype, value, isPrimaryKey
        String baseTableName = triggerRequest.getBaseTableName();
        Row curExistingRecordPreAggView = null;
        Row lastExistingRecordPreAggView = null;
        String curExistingPKPreAggViewValue = "";
        PrimaryKey preAggViewTablePK = null;
        String functionColNamePreAgg = "";

        // Populating values for aggregation key data
        Map<String, ColumnDefinition> preaggTableDesc = ViewMaintenanceUtilities.getTableDefinitition(preaggTable.getKeySpace(),
                preaggTable.getName());

        logger.debug("### PreAgg View Table Desc  ::: " + preaggTableDesc);

        for ( Map.Entry<String, ColumnDefinition> preaggColEntry : preaggTableDesc.entrySet() ) {
            LinkedTreeMap dataJson = triggerRequest.getDataJson();
            String derivedPrefix = preaggColEntry.getKey().substring(0, preaggColEntry.getKey()
                    .indexOf("_"));
            String derivedColumnName = preaggColEntry.getKey().substring(preaggColEntry.getKey()
                    .indexOf("_") + 1);

            if ( AVAILABLE_FUNCS.contains(derivedPrefix) ) {
                List<String> tempList = new ArrayList<>();
                functionColNamePreAgg = preaggColEntry.getKey();
                tempList.add(preaggColEntry.getValue()
                        .type.toString());
                tempList.add("");
                tempList.add("false");
                userDataCur.put(preaggColEntry.getKey(), tempList);
            } else if ( preaggColEntry.getValue().isPartitionKey() ) {
                curExistingPKPreAggViewValue = ((String) dataJson.get(derivedColumnName)).replaceAll("'", "");
                preAggViewTablePK = new PrimaryKey(preaggColEntry.getKey(), preaggColEntry.getValue().type.toString(),
                        curExistingPKPreAggViewValue);
                try {
                    curExistingRecordPreAggView = ViewMaintenanceUtilities.getExistingRecordIfExists(preAggViewTablePK,
                            preaggTable);
                } catch ( SocketException e ) {
                    logger.error("Error!!! " + ViewMaintenanceUtilities.getStackTrace(e));
                }

                List<String> tempList = new ArrayList<>();
                tempList.add(preaggColEntry.getValue()
                        .type.toString());
                tempList.add(curExistingPKPreAggViewValue);
                tempList.add("true");
                userDataCur.put(derivedColumnName, tempList);
                aggregationKeyData.add(derivedColumnName);
                aggregationKeyData.add(preaggColEntry.getValue()
                        .type.toString());
                aggregationKeyData.add(curExistingPKPreAggViewValue);
            }
        }

        logger.debug("### Tentative cur user data with all the values :: " + userDataCur);
        logger.debug("### Column name for the function to be applied on :: " + functionColNamePreAgg);
        logger.debug("### Cur existing record in the pre aggregation view :: " + curExistingRecordPreAggView);

        if ( curExistingRecordPreAggView != null ) {

            // Adding the value for the function_targetcol from existing record in preagg table
            List<String> functionTargetColDataList = userDataCur.get(functionColNamePreAgg);
            functionTargetColDataList.set(1, curExistingRecordPreAggView.getInt(functionColNamePreAgg) + "");
            userDataCur.put(functionColNamePreAgg, functionTargetColDataList);

            logger.debug("### Final cur user data with all the values :: " + userDataCur);

            // check for the change in aggregation key
            String statusEntryColAggKey = ViewMaintenanceUtilities.checkForChangeInAggregationKeyInDeltaView(aggregationKeyData,
                    deltaTableRecord);

            // Insert the curr agg key data into the result view table
            List<List<String>> curAggregationKeyData = new ArrayList<>();
            curAggregationKeyData.add(aggregationKeyData);

            List<String> curFunctionTargetColumnData = new ArrayList<>(); // Format:  nameOfColumn, cassandraInternalType, valueInString
            curFunctionTargetColumnData.add(functionColNamePreAgg);
            curFunctionTargetColumnData.add(ViewMaintenanceUtilities.getCassInternalDataTypeFromCQL3DataType("int"));
            curFunctionTargetColumnData.add(userDataCur.get(functionColNamePreAgg).get(1));
            curAggregationKeyData.add(curFunctionTargetColumnData);

            logger.debug("### Map for cur aggregate key data :: " + curAggregationKeyData);

            preAggActualInsertProcess(curAggregationKeyData);

            if ( statusEntryColAggKey.equals("changed") ) {

                PrimaryKey oldAggKeyPK = preAggViewTablePK;

                // Insert the old agg key data into the result view table
                if ( ViewMaintenanceUtilities.getJavaTypeFromCassandraType(aggregationKeyData.get(1))
                        .equalsIgnoreCase("Integer") ) {

                    oldAggKeyPK.setColumnValueInString((deltaTableRecord.getInt(aggregationKeyData.get(0) + "")
                            + DeltaViewTrigger.LAST));

                } else if ( ViewMaintenanceUtilities.getJavaTypeFromCassandraType(aggregationKeyData.get(1))
                        .equalsIgnoreCase("String") ) {

                    oldAggKeyPK.setColumnValueInString(deltaTableRecord.getString(aggregationKeyData.get(0)
                            + DeltaViewTrigger.LAST));
                }


                // Processing starts for old aggregate key data
                try {
                    lastExistingRecordPreAggView = ViewMaintenanceUtilities.getExistingRecordIfExists(oldAggKeyPK,
                            preaggTable);
                } catch ( SocketException e ) {
                    logger.error("Error!!! " + ViewMaintenanceUtilities.getStackTrace(e));
                }

                logger.debug("### Existing record for pre agg view for old agg key:: " + lastExistingRecordPreAggView);

                List<List<String>> oldAggregateKeyData = new ArrayList<>();
                for ( Map.Entry<String, List<String>> userDataEntry : userDataCur.entrySet() ) {
                    List<String> tempList = new ArrayList<>();
                    tempList.add(userDataEntry.getKey());
                    tempList.add(userDataEntry.getValue().get(0));
                    if ( userDataEntry.getValue().get(2).equalsIgnoreCase("true") ) {
                        tempList.add(oldAggKeyPK.getColumnValueInString());
                    } else {
                        tempList.add(lastExistingRecordPreAggView.getInt(functionColNamePreAgg) + "");
                    }
                    oldAggregateKeyData.add(tempList);

                }

                logger.debug("### Map for old aggregate key data :: " + oldAggregateKeyData);

                preAggActualInsertProcess(oldAggregateKeyData);

            }


        }


    }


    private void preAggActualInsertProcess(List<List<String>> dataList) {
        List<String> colNames = new ArrayList<>();
        List<Object> objects = new ArrayList<>();

        for ( List<String> aggregateKeyData : dataList ) {
            colNames.add(aggregateKeyData.get(0));
            if ( ViewMaintenanceUtilities.getJavaTypeFromCassandraType(aggregateKeyData.get(1))
                    .equalsIgnoreCase("Integer") ) {
                objects.add(Integer.parseInt(aggregateKeyData.get(2)));
            } else if ( ViewMaintenanceUtilities.getJavaTypeFromCassandraType(aggregateKeyData.get(1))
                    .equalsIgnoreCase("String") ) {
                objects.add(aggregateKeyData.get(2));
            }
        }


        Statement insertPreAggIntoResultQuery = QueryBuilder.insertInto(operationViewTables.get(0).getKeySpace(),
                operationViewTables.get(0).getName()).values(colNames.toArray(new String[colNames.size()]),
                objects.toArray());

        logger.debug("### Insert query source: preagg view dest: resultviewtable :: " + insertPreAggIntoResultQuery);

        try {
            CassandraClientUtilities.commandExecution(CassandraClientUtilities.getEth0Ip(), insertPreAggIntoResultQuery);
        } catch ( SocketException e ) {
            logger.error("Error!!! " + ViewMaintenanceUtilities.getStackTrace(e));
        }

    }


    private void whereInsertTrigger(TriggerRequest triggerRequest) {

        Table resultTable = operationViewTables.get(0);
        Map<String, ColumnDefinition> resultTableDesc = ViewMaintenanceUtilities.getTableDefinitition(resultTable.getKeySpace(),
                resultTable.getName());
        LinkedTreeMap dataJson = triggerRequest.getDataJson();
        Set keySet = dataJson.keySet();
        PrimaryKey whereTablePrimaryKey = null;
        Table whereTableInvolved = null;

        Map<String, ColumnDefinition> whereViewTableDesc = null;
        for ( Table whereTable : inputViewTables ) {
            String targetTableDerivedFromOperationTable = whereTable.getName().split("_")[2];
            if ( targetTableDerivedFromOperationTable.equalsIgnoreCase(triggerRequest.getBaseTableName()) ) {
                whereTableInvolved = whereTable;
                whereViewTableDesc = ViewMaintenanceUtilities.getTableDefinitition(whereTable.getKeySpace(),
                        whereTable.getName());
                break;
            }
        }

        Map<String, List<String>> userData = new HashMap<>(); // Source is DataJson and whereTable

        for ( Map.Entry<String, ColumnDefinition> columnDefinitionEntry : whereViewTableDesc.entrySet() ) {
            Iterator dataIter = keySet.iterator();
            while ( dataIter.hasNext() ) {
                String tempDataKey = (String) dataIter.next();
                logger.debug("### Checking -- Key: " + tempDataKey);
                logger.debug("### Checking -- Value: " + dataJson.get(tempDataKey));
                if ( columnDefinitionEntry.getKey().equalsIgnoreCase(tempDataKey) ) {
                    List<String> tempColValue = new ArrayList<>();
                    tempColValue.add(columnDefinitionEntry.getValue().type.toString());
                    if ( ViewMaintenanceUtilities.getJavaTypeFromCassandraType(columnDefinitionEntry.getValue()
                            .type.toString()).equalsIgnoreCase("Integer") ) {
                        tempColValue.add((String) dataJson.get(tempDataKey));
                    } else if ( ViewMaintenanceUtilities.getJavaTypeFromCassandraType(columnDefinitionEntry.getValue()
                            .type.toString()).equalsIgnoreCase("String") ) {
                        tempColValue.add(((String) dataJson.get(tempDataKey)).replaceAll("'", ""));
                    }
                    if ( columnDefinitionEntry.getValue().isPartitionKey() ) {
                        tempColValue.add("true");
                        whereTablePrimaryKey = new PrimaryKey(columnDefinitionEntry.getKey(), columnDefinitionEntry.getValue().type.toString(),
                                ((String) dataJson.get(tempDataKey)).replaceAll("'", ""));
                        logger.debug("### whereTablePrimaryKey :: " + whereTablePrimaryKey);
                    } else {
                        tempColValue.add("false");
                    }
                    userData.put(columnDefinitionEntry.getKey(), tempColValue);
                    break;
                }
            }
        }
        logger.debug("### User data created out of the where view table and dataJson :: " + userData);

        // Query the where table if this data is there or not
        Row existingRecordWhereTable = null;
        try {
            existingRecordWhereTable = ViewMaintenanceUtilities.getExistingRecordIfExists(whereTablePrimaryKey, whereTableInvolved);
        } catch ( SocketException e ) {
            logger.error("Error!!! " + ViewMaintenanceUtilities.getStackTrace(e));
        }


        // Map for result view table along the lines of userData
        Map<String, List<String>> userDataForResultViewTable = new HashMap<>();
        PrimaryKey resultTablePrimaryKey = null;

        // Mapping where view table vs result view table


        for ( Map.Entry<String, List<String>> userDataCol : userData.entrySet() ) {
            for ( Map.Entry<String, ColumnDefinition> resultViewCol : resultTableDesc.entrySet() ) {
                if ( userDataCol.getKey().equalsIgnoreCase(resultViewCol.getKey()) ||
                        resultViewCol.getKey().equalsIgnoreCase(userDataCol.getKey() + "_temp") ) {
                    List<String> tempColValResultView = new ArrayList<>();
                    tempColValResultView.add(resultViewCol.getValue().type.toString());
                    tempColValResultView.add(userDataCol.getValue().get(1));
                    tempColValResultView.add(userDataCol.getValue().get(2));
                    userDataForResultViewTable.put(resultViewCol.getKey(), tempColValResultView);
                    if ( userDataCol.getValue().get(2).equalsIgnoreCase("true") ) {
                        resultTablePrimaryKey = new PrimaryKey(resultViewCol.getKey(), resultViewCol.getValue().type.toString(),
                                userDataCol.getValue().get(1));
                    }
                    break;
                }
            }
        }

        logger.debug("### Result View table record created :: " + userDataForResultViewTable);
        logger.debug("### resultTablePrimaryKey :: " + resultTablePrimaryKey);

        if ( existingRecordWhereTable != null ) {
            logger.debug("#### Existing record in the whereViewTable #### " + existingRecordWhereTable);

            Row existingRecordResultView = null;
            try {
                existingRecordResultView = ViewMaintenanceUtilities.getExistingRecordIfExists(resultTablePrimaryKey,
                        resultTable);
            } catch ( SocketException e ) {
                logger.error("Error!!! " + ViewMaintenanceUtilities.getStackTrace(e));
            }

            if ( existingRecordResultView != null ) {
                // Get existing record from the result view and update
                logger.debug("### Record already exists... Update of the old record is in progress!! " + existingRecordResultView);
                updateResultView(resultTablePrimaryKey, userDataForResultViewTable);
            } else {
                // Fresh new insert
                logger.debug("### New entry for the record");
                insertIntoResultView(userDataForResultViewTable);
            }


        } else {

            logger.debug("#### Record does not exist in the whereViewTable ####");
            // Either it is not there or if it is there then it needs to be deleted from result view
            Row existingRecordResultView = null;
            try {
                existingRecordResultView = ViewMaintenanceUtilities.getExistingRecordIfExists(resultTablePrimaryKey,
                        resultTable);
            } catch ( SocketException e ) {
                logger.error("Error!!! " + ViewMaintenanceUtilities.getStackTrace(e));
            }

            if ( existingRecordResultView != null ) {
                logger.debug("#### Existing record found in the result view table :: " + existingRecordResultView);
                deleteFromResultView(resultTablePrimaryKey);
            } else {
                // Nothing needs to be done
                logger.debug("### Record was not found in the result view table and need not to be dealt with!!!");
                return;
            }
        }


    }


    private void deleteFromResultView(PrimaryKey resultTablePrimaryKey) {
        Statement deleteQuery = null;

        if ( resultTablePrimaryKey.getColumnJavaType().equalsIgnoreCase("Integer") ) {

            deleteQuery = QueryBuilder.delete().from(operationViewTables.get(0).getKeySpace(),
                    operationViewTables.get(0).getName()).where(QueryBuilder.eq(resultTablePrimaryKey.getColumnName(),
                    Integer.parseInt(resultTablePrimaryKey.getColumnValueInString())));
        } else if ( resultTablePrimaryKey.getColumnJavaType().equalsIgnoreCase("String") ) {

            deleteQuery = QueryBuilder.delete().from(operationViewTables.get(0).getKeySpace(),
                    operationViewTables.get(0).getName()).where(QueryBuilder.eq(resultTablePrimaryKey.getColumnName(),
                    resultTablePrimaryKey.getColumnValueInString()));
        }

        logger.debug("#### Delete query from result view table :: " + deleteQuery);
        try {
            CassandraClientUtilities.commandExecution(CassandraClientUtilities.getEth0Ip(), deleteQuery);
        } catch ( SocketException e ) {
            logger.error("Error!!! " + ViewMaintenanceUtilities.getStackTrace(e));
        }

    }


    private void updateResultView(PrimaryKey resultTablePrimaryKey, Map<String, List<String>> userDataForResult) {

        Update.Assignments assignments = QueryBuilder.update(operationViewTables.get(0).getKeySpace(),
                operationViewTables.get(0).getName()).with();
        Statement updateQuery = null;
        for ( Map.Entry<String, List<String>> userDataCol : userDataForResult.entrySet() ) {
            if ( userDataCol.getValue().get(2).equalsIgnoreCase("true") ) {
                // For primary key
                if ( resultTablePrimaryKey.getColumnJavaType().equalsIgnoreCase("Integer") ) {
                    updateQuery = assignments.where(QueryBuilder.eq(resultTablePrimaryKey.getColumnName(),
                            Integer.parseInt(resultTablePrimaryKey.getColumnValueInString())));
                } else if ( resultTablePrimaryKey.getColumnJavaType().equalsIgnoreCase("String") ) {
                    updateQuery = assignments.where(QueryBuilder.eq(resultTablePrimaryKey.getColumnName(),
                            resultTablePrimaryKey.getColumnValueInString()));
                }
            } else {
                // For non-primary key
                if ( ViewMaintenanceUtilities.getJavaTypeFromCassandraType(userDataCol.getValue().get(0))
                        .equalsIgnoreCase("Integer") ) {
                    assignments.and(QueryBuilder.add(userDataCol.getKey(), Integer.parseInt(userDataCol.getValue().get(1))));
                } else if ( ViewMaintenanceUtilities.getJavaTypeFromCassandraType(userDataCol.getValue().get(0))
                        .equalsIgnoreCase("String") ) {
                    assignments.and(QueryBuilder.add(userDataCol.getKey(), userDataCol.getValue().get(1)));
                }

            }


            logger.debug("### Final update query for result view operation maintenance :: " + updateQuery);
            try {
                CassandraClientUtilities.commandExecution(CassandraClientUtilities.getEth0Ip(), updateQuery);
            } catch ( SocketException e ) {
                logger.error("Error!!! " + ViewMaintenanceUtilities.getStackTrace(e));
            }
        }
    }

    private void insertIntoResultView(Map<String, List<String>> userDataForResult) {
        List<String> colNames = new ArrayList<>();
        List<Object> objects = new ArrayList<>();

        for ( Map.Entry<String, List<String>> userDataCol : userDataForResult.entrySet() ) {
            colNames.add(userDataCol.getKey());
            if ( ViewMaintenanceUtilities.getJavaTypeFromCassandraType(userDataCol.getValue().get(0))
                    .equalsIgnoreCase("Integer") ) {
                objects.add(Integer.parseInt(userDataCol.getValue().get(1)));
            } else if ( ViewMaintenanceUtilities.getJavaTypeFromCassandraType(userDataCol.getValue().get(0))
                    .equalsIgnoreCase("String") ) {
                objects.add(userDataCol.getValue().get(1));
            }
        }

        Statement insertQuery = QueryBuilder.insertInto(operationViewTables.get(0).getKeySpace(),
                operationViewTables.get(0).getName()).values(colNames.toArray(new String[colNames.size()]),
                objects.toArray());

        logger.debug("#### Final insert query :: " + insertQuery);

        try {
            CassandraClientUtilities.commandExecution(CassandraClientUtilities.getEth0Ip(), insertQuery);
        } catch ( SocketException e ) {
            logger.error("Error!!! " + ViewMaintenanceUtilities.getStackTrace(e));
        }
    }

    @Override
    public boolean updateTrigger(TriggerRequest triggerRequest) {
        return false;
    }

    @Override
    public boolean deleteTrigger(TriggerRequest triggerRequest) {
        logger.debug("##### Entering delete trigger for Final Result Operations!!! ");
        logger.debug("##### Received elements #####");
        logger.debug("##### Table structure involved: {}", this.operationViewTables);
        this.deltaTableRecord = triggerRequest.getCurrentRecordInDeltaView();
        logger.debug("##### Delta table record {}", this.deltaTableRecord);
        logger.debug("##### Input tables structure {}", this.inputViewTables);
        logger.debug("##### Trigger request :: " + triggerRequest);

        try {
            if ( inputViewTables.size() >= 1 && inputViewTables.get(0).getName().contains(WHERE_TABLE_INDENTIFIER) ) {
                logger.debug("#### Where delete trigger for result view maintenance!!");
                whereDeleteTrigger(triggerRequest);
            } else if ( inputViewTables.size() == 1 && inputViewTables.get(0).getName().contains(JOIN_TABLE_INDENTIFIER) ) {
                logger.debug("#### Join delete trigger for result view maintenance!!");
            } else if ( inputViewTables.size() == 1 && inputViewTables.get(0).getName().contains(AGG_TABLE_INDENTIFIER) ) {
                logger.debug("#### Agg delete trigger for result view maintenance!!");
                aggDeleteTrigger(triggerRequest);
            } else if ( inputViewTables.size() == 1 && inputViewTables.get(0).getName().contains(PREAGG_TABLE_INDENTIFIER) ) {
                logger.debug("#### Preagg delete trigger for result view maintenance!!");
                preaggDeleteTrigger(triggerRequest);
            }
        } catch ( Exception e ) {
            logger.error("Error!!! " + ViewMaintenanceUtilities.getStackTrace(e));
            throw e;
        }

        return true;
    }


    public void whereDeleteTrigger(TriggerRequest triggerRequest) {
        Table whereTableConfig = ViewMaintenanceUtilities.getConcernedWhereTableFromWhereTablesList(triggerRequest, inputViewTables);

        PrimaryKey whereTablePrimaryKey = ViewMaintenanceUtilities.getPrimaryKeyFromTableConfigWithoutValue(
                whereTableConfig.getKeySpace(), whereTableConfig.getName());

        PrimaryKey resultTablePrimaryKey = ViewMaintenanceUtilities.getPrimaryKeyFromTableConfigWithoutValue(
                operationViewTables.get(0).getKeySpace(), operationViewTables.get(0).getName());

        String[] whereStringArr = triggerRequest.getWhereString().split("=");
        if ( whereTablePrimaryKey.getColumnJavaType().equalsIgnoreCase("Integer") ) {
            whereTablePrimaryKey.setColumnValueInString(whereStringArr[1].trim());
            resultTablePrimaryKey.setColumnValueInString(whereStringArr[1].trim());
        } else if ( whereTablePrimaryKey.getColumnJavaType().equalsIgnoreCase("String") ) {
            whereTablePrimaryKey.setColumnValueInString(whereStringArr[1].trim().replace("'", ""));
            resultTablePrimaryKey.setColumnValueInString(whereStringArr[1].trim().replace("'", ""));
        }

        Row recordTobeDeleted = null;
        try {
            recordTobeDeleted = ViewMaintenanceUtilities.getExistingRecordIfExists(
                    whereTablePrimaryKey, whereTableConfig);
        } catch ( SocketException e ) {
            logger.error("Error!!! " + ViewMaintenanceUtilities.getStackTrace(e));
        }

        logger.debug("### Delete from result view for primary key :: " + resultTablePrimaryKey);
        if ( recordTobeDeleted == null ) {
            deleteFromResultView(resultTablePrimaryKey);
        }

    }


    public void aggDeleteTrigger(TriggerRequest triggerRequest) {
        Table aggTableConfig = inputViewTables.get(0);
        Map<String, List<String>> userDataResultTable = null; //Format : <NameOfColumn>, <valueInString, cassandraInternalType,
        // isPrimaryKey>
        List<String> curFunctionTargetColumnData = null; // Format:  nameOfColumn, cassandraInternalType, valueInString
        Map<String, ColumnDefinition> aggTableDesc = ViewMaintenanceUtilities.getTableDefinitition(aggTableConfig
                .getKeySpace(), aggTableConfig.getName());

        PrimaryKey aggPrimaryKey = ViewMaintenanceUtilities.getPrimaryKeyFromTableConfigWithoutValue(
                aggTableConfig.getKeySpace(), aggTableConfig.getName());

        PrimaryKey resultTablePrimaryKey = ViewMaintenanceUtilities.getPrimaryKeyFromTableConfigWithoutValue(
                operationViewTables.get(0).getKeySpace(), operationViewTables.get(0).getName());

        Map<String, ColumnDefinition> resultViewTableDesc = ViewMaintenanceUtilities.getTableDefinitition(operationViewTables
                .get(0).getKeySpace(), operationViewTables.get(0).getName());

        for ( Map.Entry<String, ColumnDefinition> aggTableEntry : aggTableDesc.entrySet() ) {
            String derivedColumnName = aggTableEntry.getKey().substring(aggTableEntry.getKey().indexOf("_") + 1);
            if ( aggTableEntry.getValue().isPartitionKey() ) {
                if ( aggPrimaryKey.getColumnJavaType().equalsIgnoreCase("Integer") ) {
                    int value = deltaTableRecord.getInt(derivedColumnName + DeltaViewTrigger.CURRENT);
                    aggPrimaryKey.setColumnValueInString(value + "");
                } else if ( aggPrimaryKey.getColumnJavaType().equalsIgnoreCase("String") ) {
                    String value = deltaTableRecord.getString(derivedColumnName + DeltaViewTrigger.CURRENT);
                    aggPrimaryKey.setColumnValueInString(value);
                }
                break;
            }
        }

        logger.debug("#### aggPrimaryKey :: " + aggPrimaryKey);

        Row recordTobeDeleted = null;
        try {
            recordTobeDeleted = ViewMaintenanceUtilities.getExistingRecordIfExists(aggPrimaryKey, aggTableConfig);
        } catch ( SocketException e ) {
            logger.error("Error!!! " + ViewMaintenanceUtilities.getStackTrace(e));
        }
        logger.debug("### Delete from result view for primary key :: " + resultTablePrimaryKey);


        if ( recordTobeDeleted == null ) {
            deleteFromResultView(resultTablePrimaryKey);
        } else {
            userDataResultTable = new HashMap<>();
            // Format::
            // Key: Name of the column
            // Value : CassandraInternalType, ValueInString, isPrimaryKeyForReverseJoinTable
            for ( Map.Entry<String, ColumnDefinition> resultTableEntry : resultViewTableDesc.entrySet() ) {
                curFunctionTargetColumnData = new ArrayList<>();
                String javaDataType = ViewMaintenanceUtilities.getJavaTypeFromCassandraType(resultTableEntry.getValue().type
                        .toString());
                curFunctionTargetColumnData.add(resultTableEntry.getValue().type.toString());

                if ( resultTableEntry.getValue().isPartitionKey() ) {
                    if ( javaDataType.equalsIgnoreCase("Integer") ) {
                        curFunctionTargetColumnData.add(recordTobeDeleted.getInt(triggerRequest.getBaseTableName()
                                + "_" + resultTableEntry.getKey()) + "");

                    } else if ( javaDataType.equalsIgnoreCase("String") ) {
                        curFunctionTargetColumnData.add(recordTobeDeleted.getString(triggerRequest.getBaseTableName()
                                + "_" + resultTableEntry.getKey()));
                    }
                    curFunctionTargetColumnData.add("true");
                } else {
                    if ( javaDataType.equalsIgnoreCase("Integer") ) {
                        curFunctionTargetColumnData.add(recordTobeDeleted.getInt(resultTableEntry.getKey()) + "");

                    } else if ( javaDataType.equalsIgnoreCase("String") ) {
                        curFunctionTargetColumnData.add(recordTobeDeleted.getString(resultTableEntry.getKey()));
                    }
                    curFunctionTargetColumnData.add("false");
                }
                userDataResultTable.put(resultTableEntry.getKey(), curFunctionTargetColumnData);
            }

            logger.debug("##### aggDeleteTrigger :: userDataResultTable :: " + userDataResultTable);
            aggActualInsertProcess(userDataResultTable);
        }
    }

    public void preaggDeleteTrigger(TriggerRequest triggerRequest) {

        Table preAggTableConfig = inputViewTables.get(0);
        List<List<String>> userDataResultTable = null; //Format : List of <nameOfColumn, cassandraInternalType, valueInString>
        List<String> curFunctionTargetColumnData = null; // Format:  nameOfColumn, cassandraInternalType, valueInString
        Map<String, ColumnDefinition> preAggTableDesc = ViewMaintenanceUtilities.getTableDefinitition(preAggTableConfig
                .getKeySpace(), preAggTableConfig.getName());

        PrimaryKey preAggPrimaryKey = ViewMaintenanceUtilities.getPrimaryKeyFromTableConfigWithoutValue(
                preAggTableConfig.getKeySpace(), preAggTableConfig.getName());

        PrimaryKey resultTablePrimaryKey = ViewMaintenanceUtilities.getPrimaryKeyFromTableConfigWithoutValue(
                operationViewTables.get(0).getKeySpace(), operationViewTables.get(0).getName());

        Map<String, ColumnDefinition> resultViewTableDesc = ViewMaintenanceUtilities.getTableDefinitition(operationViewTables
                .get(0).getKeySpace(), operationViewTables.get(0).getName());

        for ( Map.Entry<String, ColumnDefinition> preAggTableEntry : preAggTableDesc.entrySet() ) {
            String derivedColumnName = preAggTableEntry.getKey().substring(preAggTableEntry.getKey().indexOf("_") + 1);
//            String prefixForColName = preAggTableEntry.getKey().substring(0, preAggTableEntry.getKey().indexOf("_"));
            if ( preAggTableEntry.getValue().isPartitionKey() ) {
                if ( preAggPrimaryKey.getColumnJavaType().equalsIgnoreCase("Integer") ) {
                    int value = deltaTableRecord.getInt(derivedColumnName + DeltaViewTrigger.CURRENT);
                    preAggPrimaryKey.setColumnValueInString(value + "");
                } else if ( preAggPrimaryKey.getColumnJavaType().equalsIgnoreCase("String") ) {
                    String value = deltaTableRecord.getString(derivedColumnName + DeltaViewTrigger.CURRENT);
                    preAggPrimaryKey.setColumnValueInString(value);
                }
                break;
            }
        }

        logger.debug("#### preAggPrimaryKey :: " + preAggPrimaryKey);

        Row recordTobeDeleted = null;
        try {
            recordTobeDeleted = ViewMaintenanceUtilities.getExistingRecordIfExists(preAggPrimaryKey, preAggTableConfig);
        } catch ( SocketException e ) {
            logger.error("Error!!! " + ViewMaintenanceUtilities.getStackTrace(e));
        }
        logger.debug("### Delete from result view for primary key :: " + resultTablePrimaryKey);


        if ( recordTobeDeleted == null ) {
            deleteFromResultView(resultTablePrimaryKey);
        } else {
            userDataResultTable = new ArrayList<>();
            for ( Map.Entry<String, ColumnDefinition> resultTableEntry : resultViewTableDesc.entrySet() ) {
                curFunctionTargetColumnData = new ArrayList<>();
                String javaDataType = ViewMaintenanceUtilities.getJavaTypeFromCassandraType(resultTableEntry.getValue().type
                        .toString());
                curFunctionTargetColumnData.add(resultTableEntry.getKey());
                curFunctionTargetColumnData.add(resultTableEntry.getValue().type.toString());
                if ( resultTableEntry.getValue().isPartitionKey() ) {
                    if ( javaDataType.equalsIgnoreCase("Integer") ) {
                        curFunctionTargetColumnData.add(recordTobeDeleted.getInt(triggerRequest.getBaseTableName()
                                + "_" + resultTableEntry.getKey()) + "");
                    } else if ( javaDataType.equalsIgnoreCase("String") ) {
                        curFunctionTargetColumnData.add(recordTobeDeleted.getString(triggerRequest.getBaseTableName()
                                + "_" + resultTableEntry.getKey()));
                    }
                } else {
                    if ( javaDataType.equalsIgnoreCase("Integer") ) {
                        curFunctionTargetColumnData.add(recordTobeDeleted.getInt(resultTableEntry.getKey()) + "");
                    } else if ( javaDataType.equalsIgnoreCase("String") ) {
                        curFunctionTargetColumnData.add(recordTobeDeleted.getString(resultTableEntry.getKey()));
                    }
                }
                userDataResultTable.add(curFunctionTargetColumnData);
            }

            logger.debug("##### preaggDeleteTrigger :: userDataResultTable :: " + userDataResultTable);
            preAggActualInsertProcess(userDataResultTable);
        }


    }

    public static ResultViewOperation getInstance(List<Table> inputViewTables,
                                                  List<Table> operationViewTables) {
        ResultViewOperation resultViewOperation = new ResultViewOperation();
        resultViewOperation.setInputViewTables(inputViewTables);
        resultViewOperation.setOperationViewTables(operationViewTables);
        return resultViewOperation;
    }

//    public void setDeltaTableRecord(Row deltaTableRecord) {
//        this.deltaTableRecord = deltaTableRecord;
//    }


    public void setInputViewTables(List<Table> inputViewTables) {
        this.inputViewTables = inputViewTables;
    }

    public void setOperationViewTables(List<Table> operationViewTables) {
        this.operationViewTables = operationViewTables;
    }

    @Override
    public String toString() {
        return "ResultViewOperation{" +
                ",\n inputViewTables=" + inputViewTables +
                ",\n operationViewTables=" + operationViewTables +
                '}';
    }
}
