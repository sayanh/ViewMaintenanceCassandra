package de.tum.viewmaintenance.Operations;

import com.datastax.driver.core.Row;
import de.tum.viewmaintenance.view_table_structure.Table;

import java.util.List;

/**
 * Created by shazra on 8/14/15.
 */
public class WhereOperation extends GenericOperation {

    private Row deltaTableRecord;
    private GenericOperation sqlOperation;
    private List<Table> inputViewTable;
    private List<Table> operationViewTables;

    public Row getDeltaTableRecord() {
        return deltaTableRecord;
    }

    public GenericOperation getSqlOperation() {
        return sqlOperation;
    }

    public void setSqlOperation(GenericOperation sqlOperation) {
        this.sqlOperation = sqlOperation;
    }

    public List<Table> getInputViewTable() {
        return inputViewTable;
    }

    public void setInputViewTable(List<Table> inputViewTable) {
        this.inputViewTable = inputViewTable;
    }

    public void setOperationViewTable(List<Table> operationViewTables) {
        this.operationViewTables = operationViewTables;
    }

    public void setDeltaTableRecord(Row deltaTableRecord) {
        this.deltaTableRecord = deltaTableRecord;
    }

    public static WhereOperation getInstance(Row deltaTableRecord, List<Table> inputViewTable,
                                        List<Table> operationViewTable) {
        WhereOperation whereOperation = new WhereOperation();
        whereOperation.setDeltaTableRecord(deltaTableRecord);
        whereOperation.setInputViewTable(inputViewTable);
        whereOperation.setOperationViewTable(operationViewTable);
        return whereOperation;
    }

    @Override
    public boolean insertTrigger() {
        return false;
    }

    @Override
    public boolean updateTrigger() {
        return false;
    }

    @Override
    public boolean deleteTrigger() {
        return false;
    }

}
