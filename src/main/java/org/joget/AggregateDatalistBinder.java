package org.joget;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.joget.apps.app.dao.FormDefinitionDao;
import org.joget.apps.app.model.FormDefinition;
import org.joget.apps.app.service.AppService;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.lib.PasswordField;
import org.joget.apps.form.model.Element;
import org.joget.apps.form.model.Form;
import org.joget.apps.form.model.FormContainer;
import org.joget.apps.form.service.FormService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.commons.beanutils.BeanUtils;
import org.hibernate.dialect.function.SQLFunction;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import java.lang.reflect.Method;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.datalist.model.DataList;
import org.joget.apps.datalist.model.DataListBinderDefault;
import org.joget.apps.datalist.model.DataListCollection;
import org.joget.apps.datalist.model.DataListColumn;
import org.joget.apps.datalist.model.DataListFilterQueryObject;
import org.joget.apps.form.service.FormUtil;
import org.joget.apps.userview.model.Userview;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.ResourceBundleUtil;
import org.joget.commons.util.UuidGenerator;
import org.springframework.orm.hibernate4.LocalSessionFactoryBean;
import org.hibernate.type.StandardBasicTypes;

public class AggregateDatalistBinder extends DataListBinderDefault{
    
    public static int MAXROWS = 10000;
    public static String ALIAS = "temp";
    private DataListColumn[] columns;
    protected String driver;
    private Form cachedForm = null;
    private String cachedTableName = null;
    private String cachedFormDefId = null;
    
    @Override
    public String getClassName() {
        return this.getClass().getName();
    }

    @Override
    public String getName() {
        return "Aggregated Form Data Binder (MySQL)";
    }

    @Override
    public String getVersion() {
        return "8.0.1";
    }

    @Override
    public String getDescription() {
        return "Retrieves data rows from a form table using Aggregate function";
    }

    @Override
    public String getLabel() {
        return "Aggregated Form Data Binder (MySQL)";
    }
    
    @Override
    public String getPrimaryKeyColumnName() {
        return "id";
        
//        String primaryKey = "";
//        Map props = getProperties();
//        if (props != null) {
//            primaryKey = props.get("primaryKey").toString();
//        }
//        return (!primaryKey.equalsIgnoreCase("")) ? primaryKey : "";
    }
    
    @Override
    public String getPropertyOptions() {
        String formDefField = null;
        AppDefinition appDef = AppUtil.getCurrentAppDefinition();
        if (appDef != null) {
            String formJsonUrl = "[CONTEXT_PATH]/web/json/console/app/" + appDef.getId() + "/" + appDef.getVersion() + "/forms/options";
            formDefField = "{name:'formDefId',label:'@@datalist.formrowdatalistbinder.formId@@',type:'selectbox',options_ajax:'" + formJsonUrl + "'}";
        } else {
            formDefField = "{name:'formDefId',label:'@@datalist.formrowdatalistbinder.formId@@',type:'textfield'}";
        }
        //formDefField += ",{name : 'primaryKey', label : '@@datalist.jdbcDataListBinder.query.primaryKey@@', type : 'textfield', value : 'id', required : 'true'}";
        
        Object[] arguments = new Object[]{formDefField};
        String json = AppUtil.readPluginResource(getClass().getName(), "/properties/aggregateDatalistBinder.json", arguments, true, "message/aggregateDatalistBinder");
        return json;
    }
    
    protected String getTableName(String formDefId) {
        String tableName = cachedTableName;
        if (tableName == null) {
            AppDefinition appDef = AppUtil.getCurrentAppDefinition();
            if (appDef != null && formDefId != null) {
                AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
                tableName = appService.getFormTableName(appDef, formDefId);
                cachedTableName = tableName;
            }
        }
        return tableName;
    }
    
    protected Form getSelectedForm() {
        Form form = null;
        FormDefinitionDao formDefinitionDao = (FormDefinitionDao) AppUtil.getApplicationContext().getBean("formDefinitionDao");
        FormService formService = (FormService) AppUtil.getApplicationContext().getBean("formService");
        String formDefId = getPropertyString("formDefId");
        if (formDefId != null) {
            if (cachedForm == null || !formDefId.equals(cachedFormDefId)) {
                AppDefinition appDef = AppUtil.getCurrentAppDefinition();
                FormDefinition formDef = formDefinitionDao.loadById(formDefId, appDef);
                if (formDef != null) {
                    String formJson = formDef.getJson();
                    
                    if (formJson != null) {
                        form = (Form) formService.createElementFromJson(formJson, false);
                        cachedFormDefId = formDefId;
                        cachedForm = form;
                    }
                }
            } else {
                form = cachedForm;
            }
        }
        return form;
    }
    
    @Override
    public DataListColumn[] getColumns() {
        List<DataListColumn> columns = new ArrayList<DataListColumn>();

        // retrieve columns
        Form form = getSelectedForm();
        if (form != null) {
            FormDataDao formDataDao = (FormDataDao) AppUtil.getApplicationContext().getBean("formDataDao");
            String tableName = formDataDao.getFormTableName(form);
            Collection<String> columnNames = formDataDao.getFormDefinitionColumnNames(tableName);
            for (String columnName : columnNames) {
                Element element = FormUtil.findElement(columnName, form, null, true);
                if (element != null && !(element instanceof FormContainer)) {
                    if (!(element instanceof PasswordField)) {
                        String id = element.getPropertyString(FormUtil.PROPERTY_ID);
                        String label = element.getPropertyString(FormUtil.PROPERTY_LABEL);
                        if (id != null && !id.isEmpty()) {
                            if (label == null || label.isEmpty()) {
                                label = id;
                            }
                            columns.add(new DataListColumn("c_" + id, label, true));
                            columns.add(new DataListColumn("COUNT_c_" + columnName, label + " (COUNT)", true));
                            columns.add(new DataListColumn("SUM_c_" + columnName, label + " (SUM)", true));
                            columns.add(new DataListColumn("AVG_c_" + columnName, label + " (AVG)", true));
                            columns.add(new DataListColumn("MIN_c_" + columnName, label + " (MIN)", true));
                            columns.add(new DataListColumn("MAX_c_" + columnName, label + " (MAX)", true));
                        }
                    }
                } else {
                    columns.add(new DataListColumn("c_" + columnName, columnName, true));
                    columns.add(new DataListColumn("COUNT_c_" + columnName, columnName + " (COUNT)", true));
                    columns.add(new DataListColumn("SUM_c_" + columnName, columnName + " (SUM)", true));
                    columns.add(new DataListColumn("AVG_c_" + columnName, columnName + " (AVG)", true));
                    columns.add(new DataListColumn("MIN_c_" + columnName, columnName + " (MIN)", true));
                    columns.add(new DataListColumn("MAX_c_" + columnName, columnName + " (MAX)", true));
                }
            }
        }
        
        columns.add(0, new DataListColumn("count", "COUNT(*)", true));
        
        // add default metadata fields
        columns.add(0, new DataListColumn(FormUtil.PROPERTY_DATE_MODIFIED, ResourceBundleUtil.getMessage("datalist.formrowdatalistbinder.dateModified"), true));
        columns.add(0, new DataListColumn(FormUtil.PROPERTY_DATE_CREATED, ResourceBundleUtil.getMessage("datalist.formrowdatalistbinder.dateCreated"), true));
        columns.add(0, new DataListColumn(FormUtil.PROPERTY_ID, ResourceBundleUtil.getMessage("datalist.formrowdatalistbinder.id"), true));

        return columns.toArray(new DataListColumn[0]);
    }
    
    protected String getQuerySelect(DataList dataList, Map properties, DataListFilterQueryObject filterQueryObject, String sort, Boolean desc, Integer start, Integer rows) {
        //TODO: use proper SQL parser to handle sorting and filtering
        String selectColumns = "";
        String groupColumns = "";
        
        String formDefId = getPropertyString("formDefId");
        String tableName = getTableName(formDefId);
        
        DataListColumn[] columns = dataList.getColumns();
        if (columns != null) {
            for (DataListColumn column : columns) {
                String columnName = "";
                columnName = column.getName();
                //columnName = columnName.replace("'", "");
                
                if(column.getName().startsWith("AVG_c_") || column.getName().startsWith("SUM_c_") || 
                   column.getName().startsWith("MIN_c_") || column.getName().startsWith("MAX_c_") || 
                   column.getName().startsWith("COUNT_c_")){
                    //columnName = columnName.replace("_c_", "(c_") + ")";
                    columnName = columnName.replace("_c_", "( CAST( c_") + " AS DECIMAL(20,2) ) )";
                    selectColumns += columnName + " AS " + column.getName() + ",";
                }else{
                    selectColumns += columnName + ",";
                    groupColumns += columnName + ",";
                }
            }
        }
        selectColumns = selectColumns.substring(0, selectColumns.length()-1);
        groupColumns = groupColumns.substring(0, groupColumns.length()-1);
        
        String sql = "app_fd_" + tableName;
        
        if(groupColumns.equalsIgnoreCase("")){
            //select all
            sql = "SELECT * FROM " + sql + " " + ALIAS;
        }else{
            sql = "SELECT " + selectColumns + " FROM " + sql + " " + ALIAS;
        }
        
        if (filterQueryObject != null) {
            sql = insertQueryCriteria(sql, properties, filterQueryObject);
        }
        
        if(!groupColumns.equalsIgnoreCase("")){
            sql += " GROUP BY " + groupColumns;
        }
        
        sql = insertQueryOrdering(sql, sort, desc);
        return sql;
    }
    
    protected String insertQueryCriteria(String sql, Map properties, DataListFilterQueryObject filterQueryObject) {
        Collection<String> params = new ArrayList<String>();
        String extraCondition = (properties.get("extraCondition") != null) ? properties.get("extraCondition").toString() : null;
        String keyName = null;
        String condition = "";
        
        if (properties.get(Userview.USERVIEW_KEY_NAME) != null) {
            keyName = properties.get(Userview.USERVIEW_KEY_NAME).toString();
        }
        String keyValue = null;
        if (properties.get(Userview.USERVIEW_KEY_VALUE) != null) {
            keyValue = properties.get(Userview.USERVIEW_KEY_VALUE).toString();
        }

        if (extraCondition != null && extraCondition.contains(USERVIEW_KEY_SYNTAX)) {
            if (keyValue == null) {
                keyValue = "";
            }
            extraCondition = extraCondition.replaceAll(USERVIEW_KEY_SYNTAX, keyValue);
        } else if (keyName != null && !keyName.isEmpty() && keyValue != null && !keyValue.isEmpty()) {
            if (condition.trim().length() > 0) {
                condition += " AND ";
            } else {
                condition += " WHERE ";
            }
            condition += getColumnName(keyName) + " = ?";
            params.add(keyValue);
        }

        if (extraCondition != null && !extraCondition.isEmpty()) {
            if (condition.trim().length() > 0) {
                condition += " AND ";
            } else {
                condition += " WHERE ";
            }
            condition += extraCondition;
        }
        
        if (filterQueryObject != null && filterQueryObject.getQuery() != null && filterQueryObject.getQuery().trim().length() > 0) {
            condition += filterQueryObject.getQuery();
        }
//
//        if (sql.contains(USERVIEW_KEY_SYNTAX)) {
//            if (keyValue == null) {
//                keyValue = "";
//            }
//            sql = sql.replaceAll(USERVIEW_KEY_SYNTAX, keyValue);
//        } else if (keyName != null && !keyName.isEmpty() && keyValue != null && !keyValue.isEmpty()) {
//            if (condition.trim().length() > 0) {
//                condition += "AND ";
//            }
//            condition += getName(keyName) + " = '" + keyValue + "' ";
//        }
//
//        if (condition != null && !condition.isEmpty()) {
//            sql += " WHERE " + condition;
//        }
        sql += condition;
        return sql;
    }
    
    protected String insertQueryOrdering(String sql, String sort, Boolean desc) {
        if (sql != null && sql.trim().length() > 0) {
            // add criteria
            if (sort != null && sort.trim().length() > 0) {
                //String clause = " " + getName(sort);
                //String clause = " " + getName(sort).replace("_c_", "( CAST( c_") + " AS DECIMAL(20,2) ) )";
                String clause = sort;
                if (desc != null && desc.booleanValue()) {
                    clause += " DESC";
                }
                sql += " ORDER BY " + clause;
            }
        }
        return sql;
    }
    
    protected String getName(String name) {
        if (name != null && !name.isEmpty()) {
            DataListColumn[] columns = getColumns();
            for (DataListColumn column : columns) {
                if (name.equalsIgnoreCase(column.getName())) {
                    name = column.getName();
                    break;
                }
            }

            if (name.contains(" ")) {
                name = ALIAS + ".`" + name + "`";
            } else {
                name = ALIAS + '.' + name;
            }
        }
        return name;
    }
    
    protected String getDriver() {
        if (driver == null) {
            driver = getPropertyString("jdbcDriver");
            String datasource = getPropertyString("jdbcDatasource");
            if (datasource != null && "default".equals(datasource)) {
                try {
                    DataSource ds = (DataSource) AppUtil.getApplicationContext().getBean("setupDataSource");
                    driver = BeanUtils.getProperty(ds, "driverClassName");
                } catch (Exception e) {}
            }
        }
        return driver;  
    }
    
    protected String insertQueryPaging(String sql, String sort, Boolean desc, Integer start, Integer rows) {
        if (isOptimisePagingSupported() && sql != null && sql.trim().length() > 0 && start > 0 && rows != null && rows != -1) {
            String driver = getDriver();
            if (driver.equals("com.mysql.jdbc.Driver")) {
                sql += " LIMIT " + start + ", " + rows;
            } else if (driver.equals("com.microsoft.sqlserver.jdbc.SQLServerDriver")) {
                if (!(sort != null && sort.trim().length() > 0)) {
                    sql += " ORDER BY " + getName(getPrimaryKeyColumnName());
                }
                sql += " OFFSET "+start+" ROWS FETCH NEXT "+rows+" ROWS ONLY";
            }
        }
        return sql;
    }
    
    protected boolean isOptimisePagingSupported() {
        String driver = getDriver();
        if ("true".equals(getPropertyString("optimisePaging")) && (driver.equals("com.mysql.jdbc.Driver") || driver.equals("com.microsoft.sqlserver.jdbc.SQLServerDriver"))) {
            return true;
        }
        return false;
    }
    
    protected DataListFilterQueryObject processFilterQueryObjects(DataSource ds, DataListFilterQueryObject[] filterQueryObjects) {
        for (DataListFilterQueryObject o : filterQueryObjects) {
            String query = o.getQuery();
            o.setQuery(processQuery(ds, query));
        }
        
        return processFilterQueryObjects(filterQueryObjects);
    }

    protected String renderFunction(SQLFunction function, List args, SessionFactoryImplementor factory) {
        try {
            // Hibernate 4 signature
            return (String) function.getClass()
                    .getMethod("render", org.hibernate.type.Type.class, List.class, SessionFactoryImplementor.class)
                    .invoke(function, StandardBasicTypes.STRING, args, factory);
        } catch (NoSuchMethodException ex) {
            try {
                // Hibernate 5 signature
                Object typeConfig = factory.getClass().getMethod("getTypeConfiguration").invoke(factory);

                return (String) function.getClass()
                    .getMethod("render", 
                        Class.forName("org.hibernate.type.spi.TypeConfiguration"), 
                        List.class)
                    .invoke(function, typeConfig, args);
            } catch (Exception e) {
                LogUtil.error(getClassName(), e, "Unable to render SQLFunction");
            }
        } catch (Exception ex) {
            LogUtil.error(getClassName(), ex, "");
        }
        return null;
    }
    
    protected String processFunction(SessionFactoryImplementor factory, Map functions, String query) {
        String tempQuery = query;
        Map<String, String> usedFunctions = new HashMap<String, String>(); 
        Pattern pattern = Pattern.compile("([a-zA-z0-9]+)\\(([^\\(^\\)]*)\\)");
        Matcher matcher = pattern.matcher(query);
        
        while (matcher.find()) {
            String uuid = "function_" + UuidGenerator.getInstance().getUuid();
            String functionString = matcher.group(0);
            String functionName = matcher.group(1).toLowerCase();
            String agrsString = matcher.group(2);
            tempQuery = tempQuery.replace(functionString, uuid);
            
            List args = new ArrayList();
            String[] agrsArray;
            if (agrsString.contains(" as ")) {
                agrsArray = agrsString.split(" as ");
            } else {
                agrsArray = agrsString.split(",");
            }
            if (agrsArray.length > 0) {
                for (String agr : agrsArray) {
                    args.add(agr.trim());
                }
            }
            
            SQLFunction sqlFunction = (SQLFunction) functions.get(functionName);
            if (sqlFunction != null) {
                String resultedQuery = renderFunction(sqlFunction, args, factory);

                if (resultedQuery != null) {
                    usedFunctions.put(uuid, resultedQuery);
                } else {
                    usedFunctions.put(uuid, functionString);
                }
            } else {
                usedFunctions.put(uuid, functionString);
            }
        }  
        
        if (!usedFunctions.isEmpty()) {
            tempQuery = processFunction(factory, functions, tempQuery);
            
            for (String key : usedFunctions.keySet()) {
                tempQuery = tempQuery.replace(key, usedFunctions.get(key));
            }
        }
        
        return tempQuery;
    }
    
    protected String processQuery(DataSource ds, String query) {
        if (query != null && !query.isEmpty()) {
            LocalSessionFactoryBean sf = new LocalSessionFactoryBean();
            try {
                sf.setDataSource(ds);
                try {
                    sf.afterPropertiesSet();
                } catch (Exception ex) {
                    LogUtil.error(this.getClassName(), ex, "Error initializing session factory");
                }
                SessionFactoryImplementor factory = (SessionFactoryImplementor) sf.getObject();
                Map functions = factory.getDialect().getFunctions();
                query = processFunction(factory, functions, query);
            } finally {
                sf.destroy();
            }
        }
        
        return query;
    }
    
    @Override
    public DataListCollection getData(DataList dataList, Map properties, DataListFilterQueryObject[] filterQueryObjects, String sort, Boolean desc, Integer start, Integer rows) {
        try {
            DataSource ds = createDataSource();
            DataListFilterQueryObject filter = processFilterQueryObjects(ds, filterQueryObjects);
            String sql = getQuerySelect(dataList, properties, filter, sort, desc, start, rows);

            // execute queries
            DataListCollection results;
            results = executeQuery(dataList, ds, sql, filter.getValues(), sort, desc, start, rows);

            return results;
        } catch (Exception ex) {
            LogUtil.error(AggregateDatalistBinder.class.getName(), ex, "");
            return null;
        }
    }
    
    protected DataListCollection executeQuery(DataList dataList, DataSource ds, String sql, String[] values, String sort, Boolean desc, Integer start, Integer rows) throws SQLException {
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        DataListCollection<Map> results = new DataListCollection<Map>();
        try {
            con = ds.getConnection();
            
            if (start == null || start < 0) {
                start = 0;
            }
            sql = insertQueryPaging(sql, sort, desc, start, rows);
            pstmt = con.prepareStatement(sql);
            
            if (rows != null && rows != -1) {
                int totalRowsToQuery = rows;
                if (!isOptimisePagingSupported()) {
                    totalRowsToQuery += start;
                }
                pstmt.setMaxRows(totalRowsToQuery);
            }
            
            if (values != null && values.length > 0) {
                for (int i = 0; i < values.length; i++) {
                    pstmt.setObject(i + 1, values[i]);
                }
            }
            rs = pstmt.executeQuery();
            
            DataListColumn[] columns = dataList.getColumns();
            int count = 0;
            while (rs.next()) {
                Map<String, String> row = new HashMap<String, String>();
                if (!isOptimisePagingSupported() && count++ < start) {
                    continue;
                }
                if (columns != null) {
                    for (DataListColumn column : columns) {
                        String columnName = column.getName();
                        Object obj = rs.getObject(columnName);
                        String columnValue = (obj != null) ? obj.toString() : "";
                        //handle for oracle timestamp
//                        if (obj instanceof TIMESTAMP) {
//                            TIMESTAMP timestamp = (TIMESTAMP) obj;
//                            columnValue = timestamp.stringValue();
//                        }
                        row.put(columnName, columnValue);
                        row.put(columnName.toLowerCase(), columnValue);
                    }
                }
                results.add(row);
            }
        } finally {
            try {
                if (rs != null) {
                    rs.close();
                }
            } catch(Exception e) {
            }
            try {
                if (pstmt != null) {
                    pstmt.close();
                }
            } catch(Exception e) {
            }
            try {
                if (con != null) {
                    con.close();
                }
            } catch(Exception e) {
            }
        }
        return results;
    }

    protected int executeQueryCount(DataList dataList, DataSource ds, String sql, String[] values) throws SQLException {
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        int count = -1;
        if (sql != null && sql.trim().length() > 0) {
            try {
                con = ds.getConnection();
                pstmt = con.prepareStatement(sql);
                if (values != null && values.length > 0) {
                    for (int i = 0; i < values.length; i++) {
                        pstmt.setObject(i + 1, values[i]);
                    }
                }
                rs = pstmt.executeQuery();
                if (rs.next()) {
                    count = rs.getInt(1);
                }
            } finally {
                try {
                    if (rs != null) {
                        rs.close();
                    }
                } catch(Exception e) {
                }
                try {
                    if (pstmt != null) {
                        pstmt.close();
                    }
                } catch(Exception e) {
                }
                try {
                    if (con != null) {
                        con.close();
                    }
                } catch(Exception e) {
                }
            }
        }
        return count;
    }
    
    public int getDataTotalRowCount(DataList dataList, Map properties, DataListFilterQueryObject[] filterQueryObjects) {
        try {
            DataSource ds = createDataSource();
            
            DataListFilterQueryObject filter = processFilterQueryObjects(ds, filterQueryObjects);

            String sqlCount = getQueryCount(dataList, properties, filter);
            
            int count = executeQueryCount(dataList, ds, sqlCount, filter.getValues());
            return count;
        } catch (Exception ex) {
            LogUtil.error(AggregateDatalistBinder.class.getName(), ex, "");
            return 0;
        }
    }
    
    protected String getQueryCount(DataList dataList, Map properties, DataListFilterQueryObject filterQueryObject) {
        String formDefId = getPropertyString("formDefId");
        String tableName = getTableName(formDefId);
        String sql = "SELECT COUNT(*) FROM app_fd_" + tableName + " " + ALIAS;
        
        String selectColumns = "";
        String groupColumns = "";
        DataListColumn[] columns = dataList.getColumns();
        if (columns != null) {
            for (DataListColumn column : columns) {
                String columnName = "";
                columnName = column.getName();
                //columnName = columnName.replace("'", "");
                
                if(column.getName().startsWith("AVG_c_") || column.getName().startsWith("SUM_c_") || 
                   column.getName().startsWith("MIN_c_") || column.getName().startsWith("MAX_c_") || 
                   column.getName().startsWith("COUNT_c_")){
                    //columnName = columnName.replace("_c_", "(c_") + ")";
                    columnName = columnName.replace("_c_", "( CAST( c_") + " AS DECIMAL(20,2) ) )";
                    selectColumns += columnName + " AS " + column.getName() + ",";
                }else{
                    selectColumns += columnName + ",";
                    groupColumns += columnName + ",";
                }
            }
        }
        selectColumns = selectColumns.substring(0, selectColumns.length()-1);
        groupColumns = groupColumns.substring(0, groupColumns.length()-1);
        
        sql = insertQueryCriteria(sql, properties, filterQueryObject);
        
        if(!groupColumns.equalsIgnoreCase("")){
            sql += " GROUP BY " + groupColumns;
        }
        
        //get row count
        sql = "SELECT COUNT(*) FROM (" + sql + ") tmp";
        
        return sql;
    }
    
    protected DataSource createDataSource() throws Exception {
        //Map binderProps = getProperties();
        DataSource ds = null;
        //String datasource = (String)binderProps.get("jdbcDatasource");
        //if (datasource != null && "default".equals(datasource)) {
            // use current datasource
             ds = (DataSource)AppUtil.getApplicationContext().getBean("setupDataSource");
//        } else {
//            // use custom datasource
//            Properties dsProps = new Properties();
//            dsProps.put("driverClassName", binderProps.get("jdbcDriver").toString());
//            dsProps.put("url", binderProps.get("jdbcUrl").toString());
//            dsProps.put("username", binderProps.get("jdbcUser").toString());
//            dsProps.put("password", binderProps.get("jdbcPassword").toString());
//            ds = BasicDataSourceFactory.createDataSource(dsProps);
//        }
        return ds;
    }
}
