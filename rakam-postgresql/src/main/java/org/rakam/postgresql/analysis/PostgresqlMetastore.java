package org.rakam.postgresql.analysis;

import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;
import org.postgresql.core.BaseConnection;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.analysis.metadata.AbstractMetastore;
import org.rakam.collection.FieldType;
import org.rakam.collection.SchemaField;
import org.rakam.config.ProjectConfig;
import org.rakam.postgresql.PostgresqlModule.PostgresqlVersion;
import org.rakam.util.NotExistsException;
import org.rakam.util.ProjectCollection;
import org.rakam.util.RakamException;
import org.rakam.util.ValidationUtil;

import javax.inject.Inject;
import javax.inject.Named;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static java.lang.String.format;
import static org.rakam.util.JDBCUtil.fromSql;
import static org.rakam.util.ValidationUtil.*;

public class PostgresqlMetastore
        extends AbstractMetastore {
    private final PostgresqlVersion.Version version;
    private final JDBCPoolDataSource connectionPool;
    private LoadingCache<ProjectCollection, List<SchemaField>> schemaCache;
    private LoadingCache<String, Set<String>> collectionCache;

    @Inject
    public PostgresqlMetastore(@Named("store.adapter.postgresql") JDBCPoolDataSource connectionPool, PostgresqlVersion version, EventBus eventBus) {
        super(eventBus);
        this.connectionPool = connectionPool;
        this.version = version.getVersion();

        schemaCache = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).build(new CacheLoader<ProjectCollection, List<SchemaField>>() {
            @Override
            public List<SchemaField> load(ProjectCollection key)
                    throws Exception {
                try (Connection conn = connectionPool.getConnection()) {
                    List<SchemaField> schema = getSchema(conn, key.project, key.collection);
                    if (schema == null) {
                        return ImmutableList.of();
                    }
                    return schema;
                }
            }
        });

        collectionCache = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).build(new CacheLoader<String, Set<String>>() {
            @Override
            public Set<String> load(String project)
                    throws Exception {
                try (Connection conn = connectionPool.getConnection()) {
                    ResultSet resultSet = conn.createStatement().executeQuery(
                            format("SELECT c.relname\n" +
                                            "FROM pg_catalog.pg_class c\n" +
                                            "    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace\n" +
                                            "    LEFT JOIN pg_inherits i ON (i.inhrelid = c.oid)\n" +
                                            "    WHERE n.nspname = '%s' and c.relkind IN ('r', 'p', '') and i.inhrelid is null\n" +
                                            "    AND n.nspname <> 'pg_catalog'\n" +
                                            "    AND n.nspname <> 'information_schema'\n" +
                                            "    AND n.nspname !~ '^pg_toast' AND c.relname != '_users' and c.relname not like '\\$%%' ESCAPE '\\'",
                                    checkLiteral(project)));

                    ImmutableSet.Builder<String> builder = ImmutableSet.builder();
                    while (resultSet.next()) {
                        String tableName = resultSet.getString(1);
                        builder.add(tableName);
                    }
                    return builder.build();
                }
            }
        });
    }

    public static String toSql(FieldType type) {
        switch (type) {
            case INTEGER:
                return "INT";
            case DECIMAL:
                return "DECIMAL";
            case LONG:
                return "BIGINT";
            case STRING:
                return "TEXT";
            case BOOLEAN:
            case DATE:
            case TIME:
                return type.name();
            case TIMESTAMP:
                return "timestamp with time zone";
            case DOUBLE:
                return "DOUBLE PRECISION";
            default:
                if (type.isArray()) {
                    return toSql(type.getArrayElementType()) + "[]";
                }
                if (type.isMap()) {
//                    if(type == FieldType.MAP_STRING_STRING) {
//                        return "hstore";
//                    }
                    return "jsonb";
                }
                throw new IllegalStateException("sql type couldn't converted to fieldtype");
        }
    }

    @Override
    public Map<String, List<SchemaField>> getCollections(String project) {
        try (Connection connection = connectionPool.getConnection()) {
            return getAllSchema(connection, project);
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public Set<String> getCollectionNames(String project) {
        try {
            return collectionCache.get(project);
        } catch (ExecutionException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public void createProject(String project) {
        if (ImmutableList.of("public", "information_schema", "pg_catalog").contains(project)) {
            throw new IllegalArgumentException("The name is a reserved name for Postgresql backend.");
        }
        try (Connection connection = connectionPool.getConnection()) {
            final Statement statement = connection.createStatement();
            statement.executeUpdate(format("CREATE SCHEMA %s", checkProject(project, '"')));
            statement.executeUpdate(format("CREATE FUNCTION %s.to_unixtime(timestamp) RETURNS double precision AS 'select extract(epoch from $1)' LANGUAGE SQL IMMUTABLE RETURNS NULL ON NULL INPUT",
                    checkProject(project, '"')));
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }

        super.onCreateProject(project);
    }

    @Override
    public Set<String> getProjects() {
        ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        try (Connection connection = connectionPool.getConnection()) {
            ResultSet schemas = connection.getMetaData().getSchemas();
            while (schemas.next()) {
                String tableSchem = schemas.getString("table_schem");
                if (!tableSchem.equals("information_schema") && !tableSchem.startsWith("pg_") && !tableSchem.equals("public")) {
                    builder.add(tableSchem);
                }
            }
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }
        return builder.build();
    }

    @Override
    public List<SchemaField> getCollection(String project, String collection) {
        try {
            return schemaCache.get(new ProjectCollection(project, collection));
        } catch (ExecutionException e) {
            throw Throwables.propagate(e);
        }
    }

    private List<SchemaField> getSchema(Connection connection, String project, String collection)
            throws SQLException {
        BaseConnection pgConnection = connection.unwrap(BaseConnection.class);
        List<SchemaField> schemaFields = Lists.newArrayList();

        ResultSet resultSet = pgConnection.execSQLQuery(format("SELECT a.attname, typname\n" +
                        "FROM pg_catalog.pg_class c\n" +
                        "    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace\n" +
                        "    LEFT JOIN pg_inherits i ON (i.inhrelid = c.oid)\n" +
                        "    JOIN pg_attribute a ON (a.attrelid=c.oid)\n" +
                        "    JOIN pg_type t ON (a.atttypid = t.oid)\n" +
                        "    WHERE n.nspname = '%s' and c.relname = '%s' " +
                        "    AND a.attname != '$server_time'\n" +
                        "    AND c.relkind IN ('r', 'p', '') and i.inhrelid IS NULL\n" +
                        "    AND n.nspname <> 'pg_catalog'\n" +
                        "    AND n.nspname <> 'information_schema'\n" +
                        "    AND n.nspname !~ '^pg_toast'     \n" +
                        "    AND a.attnum > 0 AND NOT a.attisdropped " +
                        "    AND a.attname != '$server_time'",
                checkLiteral(project), checkLiteral(collection)));

        while (resultSet.next()) {
            String columnName = resultSet.getString(1);
            FieldType fieldType;
            try {
                fieldType = fromSql(pgConnection.getTypeInfo().getSQLType(resultSet.getString(2)),
                        resultSet.getString(2));
            } catch (IllegalStateException e) {
                continue;
            }
            schemaFields.add(new SchemaField(columnName, fieldType));
        }
        return schemaFields.isEmpty() ? null : schemaFields;
    }

    private Map<String, List<SchemaField>> getAllSchema(Connection connection, String project)
            throws SQLException {
        BaseConnection pgConnection = connection.unwrap(BaseConnection.class);

        Map<String, List<SchemaField>> map = new HashMap<>();
        ResultSet resultSet = pgConnection.execSQLQuery(format("SELECT c.relname, a.attname, typname\n" +
                        "FROM pg_catalog.pg_class c\n" +
                        "    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace\n" +
                        "    LEFT JOIN pg_inherits i ON (i.inhrelid = c.oid)\n" +
                        "    JOIN pg_attribute a ON (a.attrelid=c.oid)\n" +
                        "    JOIN pg_type t ON (a.atttypid = t.oid)\n" +
                        "    WHERE n.nspname = '%s' and c.relkind IN ('r', 'p', '') and i.inhrelid is null\n" +
                        "    AND n.nspname <> 'pg_catalog'\n" +
                        "    AND n.nspname <> 'information_schema'\n" +
                        "    AND n.nspname !~ '^pg_toast'     \n" +
                        "    AND a.attnum > 0 AND NOT a.attisdropped AND c.relname not like '\\$%%' ESCAPE '\\' and c.relname != '_users' AND a.attname != '$server_time'",
                checkLiteral(project)));

        while (resultSet.next()) {
            String columnName = resultSet.getString(2);
            FieldType fieldType;
            try {
                fieldType = fromSql(pgConnection.getTypeInfo().getSQLType(resultSet.getString(3)),
                        resultSet.getString(3));
            } catch (IllegalStateException e) {
                continue;
            }
            map.computeIfAbsent(resultSet.getString(1), (k) -> new ArrayList<>()).add(new SchemaField(columnName, fieldType));
        }

        return map;
    }

    @Override
    public List<SchemaField> getOrCreateCollectionFields(String project, String collection, Set<SchemaField> fields)
            throws NotExistsException {
        ValidationUtil.checkCollectionValid(collection);
        return getOrCreateCollectionFieldsInternal(project, collection, fields, 20);
    }

    public List<SchemaField> getOrCreateCollectionFieldsInternal(String project, String collection, Set<SchemaField> fields, int remainingTry)
            throws NotExistsException {
        ValidationUtil.checkCollectionValid(collection);

        List<SchemaField> currentFields = new ArrayList<>();
        String query;
        Runnable task;

        if (collection.equals("_users")) {
            throw new RakamException("_users is reserved and cannot be used as collection name", BAD_REQUEST);
        }

        try (Connection connection = connectionPool.getConnection()) {
            connection.setAutoCommit(false);
            HashSet<String> strings = new HashSet<>();
            List<SchemaField> schema = getSchema(connection, project, collection);

            if (schema != null) {
                if (schema.size() > 200) {
                    throw new RakamException("200 columns are supported at most, can't add new attributes because of this restriction.", BAD_REQUEST);
                }

                for (SchemaField field : schema) {
                    strings.add(field.getName());
                    currentFields.add(field);
                }
            }

            List<SchemaField> schemaFields = fields.stream().filter(f -> !strings.contains(f.getName())).collect(Collectors.toList());
            if (currentFields.isEmpty()) {
                if (!getProjects().contains(ValidationUtil.checkProject(project))) {
                    throw new NotExistsException("Project");
                }
                String queryEnd = schemaFields.stream()
                        .map(f -> {
                            currentFields.add(f);
                            return f;
                        })
                        .filter(f -> !f.getName().equals("$server_time"))
                        .map(f -> format("%s %s NULL", checkTableColumn(f.getName()), toSql(f.getType())))
                        .collect(Collectors.joining(", "));

                if (!queryEnd.isEmpty()) {
                    queryEnd += ", ";
                }

                queryEnd += "\"$server_time\" timestamp with time zone default (current_timestamp at time zone 'UTC')";

                if (queryEnd.isEmpty()) {
                    return currentFields;
                }
                query = format("CREATE TABLE \"%s\".%s (%s) %s", project, checkCollection(stripName(collection, "collection")),
                        queryEnd, version == PostgresqlVersion.Version.PG10 ? "PARTITION BY RANGE (_time)" : "");
                task = () -> super.onCreateCollection(project, collection, schemaFields);
            } else {
                String queryEnd = schemaFields.stream()
                        .map(f -> {
                            currentFields.add(f);
                            return f;
                        })
                        .map(f -> format("ADD COLUMN %s %s NULL", checkTableColumn(f.getName()), toSql(f.getType())))
                        .collect(Collectors.joining(", "));
                if (queryEnd.isEmpty()) {
                    return currentFields;
                }
                query = format("ALTER TABLE \"%s\".\"%s\" %s", project, collection, queryEnd);
                task = () -> super.onCreateCollectionField(project, collection, schemaFields);
            }

            Statement statement = connection.createStatement();
            statement.execute(query);
            statement.close();
            connection.commit();
            connection.setAutoCommit(true);
            schemaCache.put(new ProjectCollection(project, collection), currentFields);
        } catch (SQLException e) {
            // syntax error exception
            if (e.getSQLState().equals("42601") || e.getSQLState().equals("42939")) {
                throw new IllegalStateException("One of the column names is not valid because it collides with reserved keywords in Postgresql. : " +
                        (currentFields.stream().map(SchemaField::getName).collect(Collectors.joining(", "))) +
                        "See http://www.postgresql.org/docs/devel/static/sql-keywords-appendix.html");
            } else
                // column or table already exists
                if (e.getMessage().contains("already exists")) {
                    if (remainingTry == 0) {
                        throw new RuntimeException("Unable to change schema");
                    }
                    return getOrCreateCollectionFieldsInternal(project, collection, fields, remainingTry - 1);
                } else {
                    throw new IllegalStateException(e.getMessage());
                }
        }

        task.run();
        return currentFields;
    }

    @Override
    public Map<String, Stats> getStats(Collection<String> projects) {
        if (projects.isEmpty()) {
            return ImmutableMap.of();
        }

        try (Connection conn = connectionPool.getConnection()) {
            PreparedStatement ps = conn.prepareStatement("SELECT\n" +
                    "        nspname, sum(reltuples)\n" +
                    "        FROM pg_class C\n" +
                    "        LEFT JOIN pg_namespace N ON (N.oid = C.relnamespace)\n" +
                    "        WHERE nspname = any(?) AND (relkind='r' or relkind='p') AND relname != '_users' GROUP BY 1");
            ps.setArray(1, conn.createArrayOf("text", projects.toArray()));
            ResultSet resultSet = ps.executeQuery();
            Map<String, Stats> map = new HashMap<>();

            while (resultSet.next()) {
                map.put(resultSet.getString(1), new Stats(resultSet.getLong(2), null, null));
            }

            for (String project : projects) {
                map.computeIfAbsent(project, (k) -> new Stats(0L, null, null));
            }

            return map;
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    public HashSet<String> getViews(String project) {
        try (Connection conn = connectionPool.getConnection()) {
            HashSet<String> tables = new HashSet<>();

            ResultSet tableRs = conn.getMetaData().getTables("", project, null, new String[]{"VIEW"});
            while (tableRs.next()) {
                String tableName = tableRs.getString("table_name");

                if (!tableName.startsWith("_")) {
                    tables.add(tableName);
                }
            }

            return tables;
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public void deleteProject(String project) {
        try (Connection conn = connectionPool.getConnection()) {
            conn.createStatement().execute(format("DROP SCHEMA %s CASCADE", checkProject(project, '"')));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        super.onDeleteProject(project);
    }
}
