package com.airbnb.di.hive.replication;

import com.google.common.base.Function;
import com.google.common.collect.Lists;

import com.airbnb.di.common.Container;
import com.airbnb.di.hive.common.HiveObjectSpec;
import com.airbnb.di.db.DbConnectionFactory;
import com.airbnb.di.utils.RetryableTask;
import com.airbnb.di.utils.RetryingTaskRunner;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.net.ntp.TimeStamp;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.util.StringUtils;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Note: to simplify programming, all methods are synchronized. This could be slow, so another
 * approach is for each thread to use a different DB connection for higher parallelism.
 */
public class PersistedJobInfoStore {

  private static final Log LOG = LogFactory.getLog(PersistedJobInfoStore.class);

  private static final String[] completedStateStrings = {ReplicationStatus.SUCCESSFUL.name(),
      ReplicationStatus.FAILED.name(), ReplicationStatus.NOT_COMPLETABLE.name()};

  private DbConnectionFactory dbConnectionFactory;
  private String dbTableName;
  private RetryingTaskRunner retryingTaskRunner = new RetryingTaskRunner();

  public PersistedJobInfoStore(DbConnectionFactory dbConnectionFactory, String dbTableName) {
    this.dbConnectionFactory = dbConnectionFactory;
    this.dbTableName = dbTableName;
  }

  public static String getCreateTableSql(String tableName) {
    return String.format("CREATE TABLE `%s` (\n" + "  `id` bigint(20) NOT NULL AUTO_INCREMENT,\n"
        + "  `create_time` timestamp DEFAULT 0, \n"
        + "  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,\n"
        + "  `operation` varchar(256) DEFAULT NULL,\n" + "  `status` varchar(4000) DEFAULT NULL,\n"
        + "  `src_path` varchar(4000) DEFAULT NULL,\n"
        + "  `src_cluster` varchar(256) DEFAULT NULL,\n"
        + "  `src_db` varchar(4000) DEFAULT NULL,\n" + "  `src_table` varchar(4000) DEFAULT NULL,\n"
        + "  `src_partitions` mediumtext DEFAULT NULL,\n"
        + "  `src_tldt` varchar(4000) DEFAULT NULL,\n"
        + "  `rename_to_db` varchar(4000) DEFAULT NULL,\n"
        + "  `rename_to_table` varchar(4000) DEFAULT NULL,\n"
        + "  `rename_to_partition` varchar(4000) DEFAULT NULL,\n"
        + "  `rename_to_path` varchar(4000), \n" + "  `extras` mediumtext, \n"
        + "  PRIMARY KEY (`id`),\n" + "  KEY `update_time_index` (`update_time`),\n"
        + "  KEY `src_cluster_index` (`src_cluster`),\n" + "  KEY `src_db_index` (`src_db`(767)),\n"
        + "  KEY `src_table_index` (`src_table`(767))\n" + ") ENGINE=InnoDB", tableName);
  }

  synchronized public List<PersistedJobInfo> getRunnableFromDbResilient() throws SQLException {
    final List<PersistedJobInfo> ret = new ArrayList<>();
    retryingTaskRunner.runUntilSuccessful(new RetryableTask() {
      @Override
      public void run() throws Exception {
        List<PersistedJobInfo> runnable = getRunnableFromDb();
        ret.addAll(runnable);
      }
    });
    return ret;
  }

  synchronized public void abortRunnableFromDb() throws SQLException {
    // Convert from ['a', 'b'] to "'a', 'b'"
    String completedStateList = StringUtils.join(", ",
        Lists.transform(Arrays.asList(completedStateStrings), new Function<String, String>() {
          public String apply(String s) {
            return String.format("'%s'", s);
          }
        }));
    String query = String.format("UPDATE %s SET status = 'ABORTED' " + "WHERE status NOT IN (%s)",
        dbTableName, completedStateList);
    Connection connection = dbConnectionFactory.getConnection();
    Statement statement = connection.createStatement();
    statement.execute(query);
  }

  synchronized public List<PersistedJobInfo> getRunnableFromDb() throws SQLException {
    // Convert from ['a', 'b'] to "'a', 'b'"
    String completedStateList = StringUtils.join(", ",
        Lists.transform(Arrays.asList(completedStateStrings), new Function<String, String>() {
          public String apply(String s) {
            return String.format("'%s'", s);
          }
        }));
    String query = String.format("SELECT id, create_time, operation, status, src_path, "
        + "src_cluster, src_db, " + "src_table, src_partitions, src_tldt, "
        + "rename_to_db, rename_to_table, rename_to_partition, " + "rename_to_path, extras "
        + "FROM %s WHERE status NOT IN (%s) ORDER BY id", dbTableName, completedStateList);

    List<PersistedJobInfo> persistedJobInfos = new ArrayList<>();
    Connection connection = dbConnectionFactory.getConnection();

    Statement statement = connection.createStatement();
    ResultSet rs = statement.executeQuery(query);

    while (rs.next()) {
      long id = rs.getLong("id");
      Optional<Timestamp> createTimestamp = Optional.ofNullable(rs.getTimestamp("create_time"));
      long createTime = createTimestamp.map(Timestamp::getTime).orElse(Long.valueOf(0));
      ReplicationOperation operation = ReplicationOperation.valueOf(rs.getString("operation"));
      ReplicationStatus status = ReplicationStatus.valueOf(rs.getString("status"));
      Optional srcPath = Optional.ofNullable(rs.getString("src_path")).map(Path::new);
      String srcClusterName = rs.getString("src_cluster");
      String srcDbName = rs.getString("src_db");
      String srcTableName = rs.getString("src_table");
      List<String> srcPartitionNames = new ArrayList<>();
      String partitionNamesJson = rs.getString("src_partitions");
      if (partitionNamesJson != null) {
        srcPartitionNames = ReplicationUtils.convertToList(partitionNamesJson);
      }
      Optional<String> srcObjectTldt = Optional.ofNullable(rs.getString("src_tldt"));
      Optional<String> renameToDbName = Optional.ofNullable(rs.getString("rename_to_db"));
      Optional<String> renameToTableName = Optional.ofNullable(rs.getString("rename_to_table"));
      Optional<String> renameToPartitionName =
          Optional.ofNullable(rs.getString("rename_to_partition"));
      Optional<Path> renameToPath =
          Optional.ofNullable(rs.getString("rename_to_path")).map(Path::new);
      Optional<String> extrasJson = Optional.ofNullable(rs.getString("extras"));
      Map<String, String> extras =
          extrasJson.map(ReplicationUtils::convertToMap).orElse(new HashMap<>());

      PersistedJobInfo persistedJobInfo = new PersistedJobInfo(id, createTime, operation, status,
          srcPath, srcClusterName, srcDbName, srcTableName, srcPartitionNames, srcObjectTldt,
          renameToDbName, renameToTableName, renameToPartitionName, renameToPath, extras);
      persistedJobInfos.add(persistedJobInfo);
    }
    return persistedJobInfos;
  }

  synchronized public PersistedJobInfo resilientCreate(
      final ReplicationOperation operation,
      final ReplicationStatus status,
      final Optional<Path> srcPath,
      final String srcClusterName,
      final HiveObjectSpec srcTableSpec,
      final List<String> srcPartitionNames,
      final Optional<String> srcTldt,
      final Optional<HiveObjectSpec> renameToObject,
      final Optional<Path> renameToPath,
      final Map<String,
      String> extras) {
    final PersistedJobInfo jobInfo = new PersistedJobInfo();

    final Container<PersistedJobInfo> container = new Container<PersistedJobInfo>();
    retryingTaskRunner.runUntilSuccessful(new RetryableTask() {
      @Override
      public void run() throws Exception {
        container.set(create(operation, status, srcPath, srcClusterName, srcTableSpec,
            srcPartitionNames, srcTldt, renameToObject, renameToPath, extras));
      }
    });

    return container.get();
  }

  synchronized public PersistedJobInfo create(
      ReplicationOperation operation,
      ReplicationStatus status,
      Optional<Path> srcPath,
      String srcClusterName,
      HiveObjectSpec srcTableSpec,
      List<String> srcPartitionNames,
      Optional<String> srcTldt,
      Optional<HiveObjectSpec> renameToObject,
      Optional<Path> renameToPath,
      Map<String, String> extras) throws IOException, SQLException {
    // Round to the nearest second to match MySQL timestamp resolution
    long currentTime = System.currentTimeMillis() / 1000 * 1000;

    String query = "INSERT INTO " + dbTableName + " SET " + "create_time = ?, " + "operation = ?, "
        + "status = ?, " + "src_path = ?, " + "src_cluster = ?, " + "src_db = ?, "
        + "src_table = ?, " + "src_partitions = ?, " + "src_tldt = ?, " + "rename_to_db = ?, "
        + "rename_to_table = ?, " + "rename_to_partition = ?, " + "rename_to_path = ?, "
        + "extras = ? ";

    Connection connection = dbConnectionFactory.getConnection();

    PreparedStatement ps = connection.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
    try {
      int i = 1;
      ps.setTimestamp(i++, new Timestamp(currentTime));
      ps.setString(i++, operation.toString());
      ps.setString(i++, status.toString());
      ps.setString(i++, srcPath.map(Path::toString).orElse(null));
      ps.setString(i++, srcClusterName);
      ps.setString(i++, srcTableSpec.getDbName());
      ps.setString(i++, srcTableSpec.getTableName());
      ps.setString(i++, ReplicationUtils.convertToJson(srcPartitionNames));
      ps.setString(i++, srcTldt.orElse(null));
      if (!renameToObject.isPresent()) {
        ps.setString(i++, null);
        ps.setString(i++, null);
        ps.setString(i++, null);
        ps.setString(i++, null);
      } else {
        ps.setString(i++, renameToObject.map(HiveObjectSpec::getDbName).orElse(null));
        ps.setString(i++, renameToObject.map(HiveObjectSpec::getTableName).orElse(null));
        ps.setString(i++, renameToObject.map(HiveObjectSpec::getPartitionName).orElse(null));
        ps.setString(i++, renameToPath.map(Path::toString).orElse(null));
      }
      ps.setString(i++, ReplicationUtils.convertToJson(extras));

      ps.execute();
      ResultSet rs = ps.getGeneratedKeys();
      boolean ret = rs.next();
      if (!ret) {
        // Shouldn't happen since we asked for the generated keys.
        throw new RuntimeException("Unexpected behavior!");
      }
      long id = rs.getLong(1);
      return new PersistedJobInfo(id, currentTime, operation, status, srcPath, srcClusterName,
          srcTableSpec.getDbName(), srcTableSpec.getTableName(), srcPartitionNames, srcTldt,
          renameToObject.map(HiveObjectSpec::getDbName),
          renameToObject.map(HiveObjectSpec::getTableName),
          renameToObject.map(HiveObjectSpec::getPartitionName), renameToPath, extras);
    } finally {
      ps.close();
      ps = null;
    }
  }

  synchronized public void persistHelper(PersistedJobInfo job) throws SQLException, IOException {
    String query = "INSERT INTO " + dbTableName + " SET " + "id = ?, " + "create_time = ?, "
        + "operation = ?, " + "status = ?, " + "src_path = ?, " + "src_cluster = ?, "
        + "src_db = ?, " + "src_table = ?, " + "src_partitions = ?, " + "src_tldt = ?, "
        + "rename_to_db = ?, " + "rename_to_table = ?, " + "rename_to_partition = ?, "
        + "rename_to_path = ?, " + "extras = ? " + "ON DUPLICATE KEY UPDATE " + "create_time = ?, "
        + "operation = ?, " + "status = ?, " + "src_path = ?, " + "src_cluster = ?, "
        + "src_db = ?, " + "src_table = ?, " + "src_partitions = ?, " + "src_tldt = ?, "
        + "rename_to_db = ?, " + "rename_to_table = ?, " + "rename_to_partition = ?, "
        + "rename_to_path = ?, " + "extras = ?";
    /*
     * String query = "INSERT INTO replication_jobs SET " + "id = ?, " + "operation = ?, " +
     * "status = ?, " + "src_path = ?, " + "src_cluster = ?, " + "src_db = ?, " + "src_table = ?, "
     * + "src_partition = ?, " + "src_object_serialized = ?, " + "rename_to_db = ?, " +
     * "rename_to_table = ?, " + "rename_to_partition = ? " + "ON DUPLICATE KEY UPDATE " +
     * "operation = ?, " + "status = ?, " + "src_path = ?, " + "src_cluster = ?, " + "src_db = ?, "
     * + "src_table = ?, " + "src_partition = ?, " + "src_object_serialized = ?, " +
     * "rename_to_db = ?, " + "rename_to_table = ?, " + "rename_to_partition = ?";
     */
    Connection connection = dbConnectionFactory.getConnection();
    PreparedStatement ps = connection.prepareStatement(query);
    try {
      int i = 1;
      ps.setLong(i++, job.getId());
      ps.setTimestamp(i++, new Timestamp(job.getCreateTime()));
      ps.setString(i++, job.getOperation().toString());
      ps.setString(i++, job.getStatus().toString());
      ps.setString(i++, job.getSrcPath().map(Path::toString).orElse(null));
      ps.setString(i++, job.getSrcClusterName());
      ps.setString(i++, job.getSrcDbName());
      ps.setString(i++, job.getSrcTableName());
      ps.setString(i++, ReplicationUtils.convertToJson(job.getSrcPartitionNames()));
      ps.setString(i++, job.getSrcObjectTldt().orElse(null));
      ps.setString(i++, job.getRenameToDb().orElse(null));
      ps.setString(i++, job.getRenameToTable().orElse(null));
      ps.setString(i++, job.getRenameToPartition().orElse(null));
      ps.setString(i++, job.getRenameToPath().map(Path::toString).orElse(null));
      ps.setString(i++, ReplicationUtils.convertToJson(job.getExtras()));

      // Handle the update case
      ps.setTimestamp(i++, new Timestamp(job.getCreateTime()));
      ps.setString(i++, job.getOperation().toString());
      ps.setString(i++, job.getStatus().toString());
      ps.setString(i++, job.getSrcPath().map(Path::toString).orElse(null));
      ps.setString(i++, job.getSrcClusterName());
      ps.setString(i++, job.getSrcDbName());
      ps.setString(i++, job.getSrcTableName());
      ps.setString(i++, ReplicationUtils.convertToJson(job.getSrcPartitionNames()));
      ps.setString(i++, job.getSrcObjectTldt().orElse(null));
      ps.setString(i++, job.getRenameToDb().orElse(null));
      ps.setString(i++, job.getRenameToTable().orElse(null));
      ps.setString(i++, job.getRenameToPartition().orElse(null));
      ps.setString(i++, job.getRenameToPath().map(Path::toString).orElse(null));
      ps.setString(i++, ReplicationUtils.convertToJson(job.getExtras()));

      ps.execute();
    } finally {
      ps.close();
      ps = null;
    }
  }

  synchronized public void changeStatusAndPersist(ReplicationStatus status, PersistedJobInfo job) {
    job.setStatus(status);
    persist(job);
  }

  synchronized public void persist(final PersistedJobInfo job) {
    retryingTaskRunner.runUntilSuccessful(new RetryableTask() {
      @Override
      public void run() throws Exception {
        persistHelper(job);
      }
    });
  }

  synchronized PersistedJobInfo getJob(long id) throws SQLException {
    String query = "SELECT id, create_time, operation, status, src_path, " + "src_cluster, src_db, "
        + "src_table, src_partitions, src_tldt, "
        + "rename_to_db, rename_to_table, rename_to_partition, " + "rename_to_path, extras "
        + "FROM " + dbTableName + " WHERE id = ?";

    Connection connection = dbConnectionFactory.getConnection();

    PreparedStatement ps = connection.prepareStatement(query);
    ResultSet rs = ps.executeQuery(query);

    while (rs.next()) {
      Optional<Timestamp> ts = Optional.ofNullable(rs.getTimestamp("create_time"));
      long createTime = ts.map(Timestamp::getTime).orElse(Long.valueOf(0));
      ReplicationOperation operation = ReplicationOperation.valueOf(rs.getString("operation"));
      ReplicationStatus status = ReplicationStatus.valueOf(rs.getString("status"));
      Optional<Path> srcPath = Optional.ofNullable(rs.getString("src_path")).map(Path::new);
      String srcClusterName = rs.getString("src_cluster");
      String srcDbName = rs.getString("src_db");
      String srcTableName = rs.getString("src_table");
      List<String> srcPartitionNames = new ArrayList<>();
      String partitionNamesJson = rs.getString("src_partitions");
      if (partitionNamesJson != null) {
        srcPartitionNames = ReplicationUtils.convertToList(partitionNamesJson);
      }
      Optional<String> srcObjectTldt = Optional.of(rs.getString("src_tldt"));
      Optional<String> renameToDbName = Optional.of(rs.getString("rename_to_db"));
      Optional<String> renameToTableName = Optional.of(rs.getString("rename_to_table"));
      Optional<String> renameToPartitionName = Optional.of(rs.getString("rename_to_partition"));
      Optional<Path> renameToPath = Optional.of(rs.getString("rename_to_path")).map(Path::new);
      String extrasJson = rs.getString("extras");
      Map<String, String> extras = new HashMap<>();
      if (extrasJson != null) {
        extras = ReplicationUtils.convertToMap(rs.getString("extras"));
      }

      PersistedJobInfo persistedJobInfo = new PersistedJobInfo(id, createTime, operation, status,
          srcPath, srcClusterName, srcDbName, srcTableName, srcPartitionNames, srcObjectTldt,
          renameToDbName, renameToTableName, renameToPartitionName, renameToPath, extras);
      return persistedJobInfo;
    }
    return null;
  }
}
