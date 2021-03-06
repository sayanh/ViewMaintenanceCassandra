package de.tum.viewmaintenance.view_table_structure;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Row;
import de.tum.viewmaintenance.client.CassandraClientUtilities;
import de.tum.viewmaintenance.config.ViewMaintenanceUtilities;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import org.apache.cassandra.config.ColumnDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by anarchy on 8/14/15.
 */
public class PreAggViewTable implements ViewTable {
    private static final Logger logger = LoggerFactory.getLogger(PreAggViewTable.class);

    private boolean shouldBeMaterialized = false;

    private List<Function> functionExpressions = null;

    private List<Expression> groupByExpressions = null;

    private Row deltaTableRecord;

    private ViewTable inputViewTable;

    private Table viewConfig;
    private List<Table> tables;

    private String baseTableName;

    private String TABLE_PREFIX;

    /**
     * Naming convention for preagg view tables: <view_name>_preagg
     **/


    public void setDeltaTableRecord(Row deltaTableRecord) {
        this.deltaTableRecord = deltaTableRecord;
    }

    public void setInputViewTable(ViewTable inputViewTable) {
        this.inputViewTable = inputViewTable;
    }

    @Override
    public List<Table> createTable() {
        logger.debug("###### Creating table for PreAggViewTable ######");
        List<Table> tablesCreated = new ArrayList<>();
        List<Column> newColumnsForNewTable = new ArrayList<>();
        logger.debug("#### FunctionExpressions :: ", functionExpressions);
        for ( Function function : functionExpressions ) {
            // TODO: Currently only one function expression is supported.
            Column tempCol = new Column();
            net.sf.jsqlparser.schema.Column functionColumn = (net.sf.jsqlparser.schema.Column)function.getParameters().getExpressions().get(0);
            logger.debug("### Checking --- the value of function name {}", function.getName());
            logger.debug("### Checking --- the value of function name without quotes {}", function.getName()
                    .replace("\"", ""));
            tempCol.setName(function.getName() + "_" + functionColumn.getColumnName()); // <functionName>_<targetColumnName>
            if ( function.getName().equalsIgnoreCase("SUM") ) {
                tempCol.setDataType("int");
            } else if ( function.getName().equalsIgnoreCase("COUNT") ) {
                tempCol.setDataType("int");
            } else if ( function.getName().equalsIgnoreCase("MAX") ) {
                tempCol.setDataType("int");
            } else if ( function.getName().equalsIgnoreCase("MIN") ) {
                tempCol.setDataType("int");
            }
            // Aggregate functions on all columns are not possible.
            if ( function.isAllColumns() ) {
                // TODO: Not supported yet.
//                tempCol.setIsPrimaryKey(true);
            }
            newColumnsForNewTable.add(tempCol);

            // Creating a column for which the function exists

            if ( !function.isAllColumns() ) {

                net.sf.jsqlparser.schema.Column groupByCol = (net.sf.jsqlparser.schema.Column)
                        groupByExpressions.get(0);

                Column primaryKeyColfunctionColumn = new Column();

                primaryKeyColfunctionColumn.setIsPrimaryKey(true);

                primaryKeyColfunctionColumn.setName(groupByCol.getTable().getName() + "_" +
                        groupByCol.getColumnName()); // <table_name>_<groupby_col> is the primary key

                String baseTableNameArr[] = ViewMaintenanceUtilities.getKeyspaceAndTableNameInAnArray(baseTableName);
                logger.debug("### Checking -- baseTableName =  {} ", baseTableName);


                Map<String, ColumnDefinition> baseTableDesc = ViewMaintenanceUtilities
                        .getTableDefinitition(baseTableNameArr[0], baseTableNameArr[1]);

                logger.debug("### Checking -- baseTableDesc =  {} ", baseTableDesc);
                logger.debug("### Checking -- group by column name = {} ", groupByCol.getColumnName());
                logger.debug("### Checking -- column type =  {} ",
                        baseTableDesc.get(groupByCol.getColumnName()).type + "");

                primaryKeyColfunctionColumn.setDataType(ViewMaintenanceUtilities
                        .getCQL3DataTypeFromCassandraInternalDataType(baseTableDesc.get(
                                groupByCol.getColumnName()).type + ""));

                newColumnsForNewTable.add(primaryKeyColfunctionColumn);
            }


        }

        Table newTableCreated = new Table();
        newTableCreated.setName(TABLE_PREFIX);
        newTableCreated.setColumns(newColumnsForNewTable);
        newTableCreated.setKeySpace(viewConfig.getKeySpace());
        newTableCreated.setIsMaterialized(shouldBeMaterialized);
        logger.debug("### PreAggViewTable structure created as :: " + newTableCreated);
        tablesCreated.add(newTableCreated);
        tables = tablesCreated;

        return tables;
    }

    @Override
    public void deleteTable() {

    }

    @Override
    public void materialize() {
        for ( Table newTable : getTables() ) {
            logger.debug(" Table getting materialized :: " + newTable);
            Cluster cluster = null;
            try {
                cluster = CassandraClientUtilities.getConnection(CassandraClientUtilities.getEth0Ip());
            } catch ( SocketException e ) {
                logger.debug("Error !!" + ViewMaintenanceUtilities.getStackTrace(e));
            }
            CassandraClientUtilities.createTable(cluster, newTable);
            CassandraClientUtilities.closeConnection(cluster);
        }
    }

    @Override
    public boolean shouldBeMaterialized() {
        return shouldBeMaterialized;
    }

    @Override
    public void createInMemory(List<Table> tables) {

    }

    public void setShouldBeMaterialized(boolean shouldBeMaterialized) {
        this.shouldBeMaterialized = shouldBeMaterialized;
    }

    public Table getViewConfig() {
        return viewConfig;
    }

    public void setViewConfig(Table viewConfig) {
        this.viewConfig = viewConfig;
        TABLE_PREFIX = viewConfig.getName() + "_preagg";
    }

    public List<Table> getTables() {
        return tables;
    }

    private void setTables(List<Table> tables) {
        this.tables = tables;
    }


    public List<Function> getFunctionExpressions() {
        return functionExpressions;
    }

    public void setFunctionExpressions(List<Function> functionExpressions) {
        this.functionExpressions = functionExpressions;
    }

    public void setGroupByExpressions(List<Expression> groupByExpressions) {
        this.groupByExpressions = groupByExpressions;
    }

    public String getBaseTableName() {
        return baseTableName;
    }

    public void setBaseTableName(String baseTableName) {
        this.baseTableName = baseTableName;
    }

}
