package com.airbnb.di.hive.replication.primitives;

import com.airbnb.di.hive.common.HiveObjectSpec;
import com.airbnb.di.hive.common.HiveMetastoreClient;
import com.airbnb.di.hive.common.HiveParameterKeys;
import com.airbnb.di.hive.common.HiveMetastoreException;
import com.airbnb.di.hive.replication.configuration.Cluster;
import com.airbnb.di.hive.replication.RunInfo;
import com.airbnb.di.multiprocessing.Lock;
import com.airbnb.di.multiprocessing.LockSet;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.metastore.api.Table;

import java.util.Optional;

public class DropTableTask implements ReplicationTask {

  private static final Log LOG = LogFactory.getLog(DropTableTask.class);

  private Cluster srcCluster;
  private Cluster destCluster;
  private HiveObjectSpec spec;
  private Optional<String> sourceTldt;

  public DropTableTask(
      Cluster srcCluster,
      Cluster destCluster,
      HiveObjectSpec spec,
      Optional<String> sourceTldt) {
    this.srcCluster = srcCluster;
    this.destCluster = destCluster;
    this.spec = spec;
    this.sourceTldt = sourceTldt;
  }

  @Override
  public RunInfo runTask() throws HiveMetastoreException {
    HiveMetastoreClient ms = destCluster.getMetastoreClient();
    LOG.debug("Looking to drop: " + spec);
    LOG.debug("Source TLDT is : " + sourceTldt);

    if (!sourceTldt.isPresent()) {
      LOG.error("For safety, not completing drop task since source " + " object TLDT is missing!");
      return new RunInfo(RunInfo.RunStatus.NOT_COMPLETABLE, 0);
    }
    String expectedTldt = sourceTldt.get();

    Table destTable = ms.getTable(spec.getDbName(), spec.getTableName());

    if (destTable == null) {
      LOG.warn("Missing " + spec + " on destination, so can't drop!");
      return new RunInfo(RunInfo.RunStatus.NOT_COMPLETABLE, 0);
    }

    LOG.debug("Destination object is: " + destTable);

    String destTldt = destTable.getParameters().get(HiveParameterKeys.TLDT);

    if (expectedTldt.equals(destTldt)) {
      LOG.debug(
          String.format("Destination table %s matches expected" + " TLDT (%s)", spec, destTldt));
      LOG.debug("Dropping " + spec);
      ms.dropTable(spec.getDbName(), spec.getTableName(), true);
      LOG.debug("Dropped " + spec);
      return new RunInfo(RunInfo.RunStatus.SUCCESSFUL, 0);
    } else {
      LOG.debug(
          String.format("Not dropping %s as source(%s) and " + "destination(%s) TLDT's dont match",
              spec.toString(), expectedTldt, destTldt));
      return new RunInfo(RunInfo.RunStatus.NOT_COMPLETABLE, 0);
    }
  }

  @Override
  public LockSet getRequiredLocks() {
    LockSet lockSet = new LockSet();
    lockSet.add(new Lock(Lock.Type.EXCLUSIVE, spec.toString()));
    return lockSet;
  }
}
