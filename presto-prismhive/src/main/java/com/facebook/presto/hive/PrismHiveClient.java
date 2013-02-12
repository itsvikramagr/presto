package com.facebook.presto.hive;

import com.facebook.presto.hive.shaded.org.apache.thrift.transport.TTransportException;
import com.facebook.presto.spi.ImportClient;
import com.facebook.presto.spi.ObjectNotFoundException;
import com.facebook.presto.spi.PartitionChunk;
import com.facebook.presto.spi.PartitionInfo;
import com.facebook.presto.spi.RecordCursor;
import com.facebook.presto.spi.SchemaField;
import com.facebook.prism.namespaceservice.PrismClient;
import com.facebook.prism.namespaceservice.PrismNamespace;
import com.facebook.prism.namespaceservice.PrismNamespaceNotFound;
import com.facebook.prism.namespaceservice.PrismRepositoryError;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.UncheckedExecutionException;
import io.airlift.units.Duration;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;

@ThreadSafe
public class PrismHiveClient
        implements ImportClient
{
    private final PrismClientProvider prismClientProvider;
    private final SmcLookup smcLookup;
    private final HiveClientFactory hiveClientFactory;
    private final HiveMetastoreClientFactory metastoreClientFactory;
    private final HiveChunkEncoder hiveChunkEncoder;
    private final Cache<String, HiveNamespace> namespaceCache;

    public PrismHiveClient(PrismClientProvider prismClientProvider, SmcLookup smcLookup, HiveClientFactory hiveClientFactory, HiveMetastoreClientFactory metastoreClientFactory, HiveChunkEncoder hiveChunkEncoder, Duration cacheDuration)
    {
        this.prismClientProvider = checkNotNull(prismClientProvider, "prismClientProvider is null");
        this.smcLookup = checkNotNull(smcLookup, "smcLookup is null");
        this.hiveClientFactory = checkNotNull(hiveClientFactory, "hiveClientFactory is null");
        this.metastoreClientFactory = checkNotNull(metastoreClientFactory, "metastoreClientFactory is null");
        this.hiveChunkEncoder = checkNotNull(hiveChunkEncoder, "hiveChunkEncoder is null");

        checkNotNull(cacheDuration, "cacheDuration is null");
        namespaceCache = CacheBuilder.newBuilder()
                .expireAfterWrite((long) cacheDuration.toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }

    // TODO: make this accessible via JMX
    public void flushCache()
    {
        namespaceCache.invalidateAll();
    }

    // Actually does the namespace resolution
    private HiveNamespace readNamespaceData(String namespace)
            throws ObjectNotFoundException
    {
        try (PrismClient prismClient = prismClientProvider.get()) {
            PrismNamespace prismNamespace = prismClient.getNamespace(namespace);
            List<HostAndPort> services = smcLookup.getServices(prismNamespace.getHiveMetastore());

            if (services.isEmpty()) {
                throw new RuntimeException("No services found for tier: " + prismNamespace.getHiveMetastore());
            }

            return new HiveNamespace(prismNamespace.getHiveMetastore(), services, prismNamespace.getHiveDatabaseName());
        }
        catch (PrismNamespaceNotFound prismNamespaceNotFound) {
            throw new ObjectNotFoundException(format("Unknown namespace: %s (raw: '%s')", namespace, prismNamespaceNotFound.getMessage()));
        }
        catch (PrismRepositoryError prismRepositoryError) {
            throw Throwables.propagate(prismRepositoryError);
        }
    }

    // Extract namespace data from the Cache and handle unwrapping exceptions
    private HiveNamespace lookupNamespace(final String namespace)
            throws ObjectNotFoundException
    {
        try {
            // Exceptions are not cached
            return namespaceCache.get(namespace, new Callable<HiveNamespace>()
            {
                @Override
                public HiveNamespace call()
                        throws Exception
                {
                    return readNamespaceData(namespace);
                }
            });
        }
        catch (ExecutionException | UncheckedExecutionException e) {
            Throwables.propagateIfInstanceOf(e.getCause(), ObjectNotFoundException.class);
            throw Throwables.propagate(e.getCause());
        }
    }

    @Override
    public List<String> getDatabaseNames()
    {
        // TODO: cache this?
        try (PrismClient prismClient = prismClientProvider.get()) {
            return prismClient.getAllNamespaceNames();
        }
        catch (PrismRepositoryError prismRepositoryError) {
            throw Throwables.propagate(prismRepositoryError);
        }
    }

    @Override
    public List<String> getTableNames(String databaseName)
            throws ObjectNotFoundException
    {
        HiveNamespace config = lookupNamespace(databaseName);
        try {
            return hiveClientFactory.get(config.getHiveCluster()).getTableNames(config.getDatabase());
        }
        catch (ObjectNotFoundException e) {
            throw new ObjectNotFoundException(format("Unknown namespace: %s (raw: '%s')", databaseName, e.getMessage()));
        }
    }

    @Override
    public List<SchemaField> getTableSchema(String databaseName, String tableName)
            throws ObjectNotFoundException
    {
        HiveNamespace config = lookupNamespace(databaseName);
        try {
            return hiveClientFactory.get(config.getHiveCluster()).getTableSchema(config.getDatabase(), tableName);
        }
        catch (ObjectNotFoundException e) {
            throw new ObjectNotFoundException(format("Unknown table: %s.%s (raw: '%s')", databaseName, tableName, e.getMessage()));
        }
    }

    @Override
    public List<SchemaField> getPartitionKeys(String databaseName, String tableName)
            throws ObjectNotFoundException
    {
        HiveNamespace config = lookupNamespace(databaseName);
        try {
            return hiveClientFactory.get(config.getHiveCluster()).getPartitionKeys(config.getDatabase(), tableName);
        }
        catch (ObjectNotFoundException e) {
            throw new ObjectNotFoundException(format("Unknown table: %s.%s (raw: '%s')", databaseName, tableName, e.getMessage()));
        }
    }

    @Override
    public List<PartitionInfo> getPartitions(String databaseName, String tableName)
            throws ObjectNotFoundException
    {
        HiveNamespace config = lookupNamespace(databaseName);
        try {
            return hiveClientFactory.get(config.getHiveCluster()).getPartitions(config.getDatabase(), tableName);
        }
        catch (ObjectNotFoundException e) {
            throw new ObjectNotFoundException(format("Unknown table: %s.%s (raw: '%s')", databaseName, tableName, e.getMessage()));
        }
    }

    @Override
    public List<PartitionInfo> getPartitions(String databaseName, String tableName, Map<String, Object> filters)
            throws ObjectNotFoundException
    {
        HiveNamespace config = lookupNamespace(databaseName);
        try {
            return hiveClientFactory.get(config.getHiveCluster()).getPartitions(config.getDatabase(), tableName, filters);
        }
        catch (ObjectNotFoundException e) {
            throw new ObjectNotFoundException(format("Unknown table: %s.%s (raw: '%s')", databaseName, tableName, e.getMessage()));
        }
    }

    @Override
    public List<String> getPartitionNames(String databaseName, String tableName)
            throws ObjectNotFoundException
    {
        HiveNamespace config = lookupNamespace(databaseName);
        try {
            return hiveClientFactory.get(config.getHiveCluster()).getPartitionNames(config.getDatabase(), tableName);
        }
        catch (ObjectNotFoundException e) {
            throw new ObjectNotFoundException(format("Unknown table: %s.%s (raw: '%s')", databaseName, tableName, e.getMessage()));
        }
    }

    @Override
    public Iterable<PartitionChunk> getPartitionChunks(String databaseName, String tableName, String partitionName, List<String> columns)
            throws ObjectNotFoundException
    {
        HiveNamespace config = lookupNamespace(databaseName);
        try {
            return hiveClientFactory.get(config.getHiveCluster()).getPartitionChunks(config.getDatabase(), tableName, partitionName, columns);
        }
        catch (ObjectNotFoundException e) {
            throw new ObjectNotFoundException(format("Unknown partition: %s.%s.%s (raw: '%s')", databaseName, tableName, partitionName, e.getMessage()));
        }
    }

    @Override
    public Iterable<PartitionChunk> getPartitionChunks(String databaseName, String tableName, List<String> partitionNames, List<String> columns)
            throws ObjectNotFoundException
    {
        HiveNamespace config = lookupNamespace(databaseName);
        try {
            return hiveClientFactory.get(config.getHiveCluster()).getPartitionChunks(config.getDatabase(), tableName, partitionNames, columns);
        }
        catch (ObjectNotFoundException e) {
            throw new ObjectNotFoundException(format("Unknown partitions: %s.%s.%s (raw: '%s')", databaseName, tableName, partitionNames.toString(), e.getMessage()));
        }
    }

    @Override
    public RecordCursor getRecords(PartitionChunk partitionChunk)
    {
        checkArgument(partitionChunk instanceof HivePartitionChunk,
                "expected instance of %s: %s", HivePartitionChunk.class, partitionChunk.getClass());
        assert partitionChunk instanceof HivePartitionChunk; // IDEA-60343
        return HiveChunkReader.getRecords((HivePartitionChunk) partitionChunk);
    }

    @Override
    public byte[] serializePartitionChunk(PartitionChunk partitionChunk)
    {
        checkArgument(partitionChunk instanceof HivePartitionChunk,
                "expected instance of %s: %s", HivePartitionChunk.class, partitionChunk.getClass());
        assert partitionChunk instanceof HivePartitionChunk; // IDEA-60343
        return hiveChunkEncoder.serialize((HivePartitionChunk) partitionChunk);
    }

    @Override
    public PartitionChunk deserializePartitionChunk(byte[] bytes)
    {
        return hiveChunkEncoder.deserialize(bytes);
    }

    private class HiveNamespace
    {
        private final PrismLocatedHiveCluster prismLocatedHiveCluster;
        private final String database;

        private HiveNamespace(String metastoreTierName, List<HostAndPort> metastores, String database)
        {
            prismLocatedHiveCluster = new PrismLocatedHiveCluster(metastoreTierName, metastores, metastoreClientFactory);
            this.database = database;
        }

        public HiveCluster getHiveCluster()
        {
            return prismLocatedHiveCluster;
        }

        public String getDatabase()
        {
            return database;
        }
    }

    @VisibleForTesting
    static class PrismLocatedHiveCluster
            implements HiveCluster
    {
        private final String metastoreTierName; // Used to determine uniqueness
        private final List<HostAndPort> services;
        private final HiveMetastoreClientFactory metastoreClientFactory;

        @VisibleForTesting
        PrismLocatedHiveCluster(String metastoreTierName, List<HostAndPort> services, HiveMetastoreClientFactory metastoreClientFactory)
        {
            Preconditions.checkArgument(!services.isEmpty(), "metastore services should not be empty");
            this.metastoreTierName = metastoreTierName;
            this.services = ImmutableList.copyOf(services);
            this.metastoreClientFactory = metastoreClientFactory;
        }

        @Override
        public HiveMetastoreClient createMetastoreClient()
        {
            TTransportException lastException = null;
            for (HostAndPort service : shuffle(services)) {
                try {
                    return metastoreClientFactory.create(service.getHostText(), service.getPort());
                }
                catch (TTransportException e) {
                    lastException = e;
                }
            }
            throw new RuntimeException("Unable to connect to any metastore servers for tier: " + metastoreTierName, lastException);
        }

        private <T> List<T> shuffle(Iterable<T> iterable)
        {
            List<T> list = Lists.newArrayList(iterable);
            Collections.shuffle(list);
            return list;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            PrismLocatedHiveCluster that = (PrismLocatedHiveCluster) o;

            return Objects.equal(metastoreTierName, that.metastoreTierName);
        }

        @Override
        public int hashCode()
        {
            return Objects.hashCode(metastoreTierName);
        }
    }
}