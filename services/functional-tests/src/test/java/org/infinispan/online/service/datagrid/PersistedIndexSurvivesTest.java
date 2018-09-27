package org.infinispan.online.service.datagrid;

import io.fabric8.openshift.client.OpenShiftClient;
import org.arquillian.cube.openshift.impl.requirement.RequiresOpenshift;
import org.arquillian.cube.requirement.ArquillianConditionalRunner;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.Search;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.marshall.ProtoStreamMarshaller;
import org.infinispan.commons.api.CacheContainerAdmin;
import org.infinispan.commons.logging.Log;
import org.infinispan.commons.logging.LogFactory;
import org.infinispan.online.service.endpoint.HotRodTester;
import org.infinispan.online.service.scaling.ScalingTester;
import org.infinispan.online.service.testdomain.AnalyzerTestEntity;
import org.infinispan.online.service.testdomain.AnalyzerTestEntityMarshaller;
import org.infinispan.online.service.utils.DeploymentHelper;
import org.infinispan.online.service.utils.OpenShiftClientCreator;
import org.infinispan.online.service.utils.OpenShiftCommandlineClient;
import org.infinispan.online.service.utils.OpenShiftHandle;
import org.infinispan.online.service.utils.ReadinessCheck;
import org.infinispan.online.service.utils.TrustStore;
import org.infinispan.protostream.FileDescriptorSource;
import org.infinispan.protostream.SerializationContext;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryFactory;
import org.infinispan.query.remote.client.MarshallerRegistration;
import org.infinispan.query.remote.client.ProtobufMetadataManagerConstants;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.InSequence;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.URL;
import java.util.List;

import static org.junit.Assert.assertEquals;

@RunWith(ArquillianConditionalRunner.class)
@RequiresOpenshift
public class PersistedIndexSurvivesTest {

   private static final Log log = LogFactory.getLog(PersistedIndexSurvivesTest.class);

   private static final String SERVICE_NAME = "datagrid-service";

   private static final String CUSTOM_ANALYZER_PROTO_SCHEMA = "package sample_bank_account;\n" +
      "/* @Indexed \n" +
      "   @Analyzer(definition = \"standard-with-stop\") */" +
      "message AnalyzerTestEntity {\n" +
      "\t/* @Field(store = Store.YES, analyze = Analyze.YES, analyzer = @Analyzer(definition = \"stemmer\")) */\n" +
      "\toptional string f1 = 1;\n" +
      "\t/* @Field(store = Store.YES, analyze = Analyze.NO, indexNullAs = \"-1\") */\n" +
      "\toptional int32 f2 = 2;\n" +
      "}\n";

   private OpenShiftClient client = OpenShiftClientCreator.getClient();

   private ReadinessCheck readinessCheck = new ReadinessCheck();
   private OpenShiftHandle handle = new OpenShiftHandle(client);

   private ScalingTester scalingTester = new ScalingTester();
   private OpenShiftCommandlineClient commandlineClient = new OpenShiftCommandlineClient();

   private RemoteCacheManager remoteCacheManager;
   private RemoteCache<String, AnalyzerTestEntity> cache;

   @Deployment
   public static Archive<?> deploymentApp() {
      return ShrinkWrap
         .create(WebArchive.class, "test.war")
         .addAsLibraries(DeploymentHelper.testLibs())
         .addPackage(ReadinessCheck.class.getPackage())
         .addPackage(ScalingTester.class.getPackage())
         .addPackage(HotRodTester.class.getPackage())
         .addPackage(AnalyzerTestEntity.class.getPackage());
   }

   @Before
   public void before() {
      readinessCheck.waitUntilAllPodsAreReady(client);
   }

   @InSequence(1)
   @Test
   public void put_on_persisted_cache() throws Exception {
      URL hotRodService = handle.getServiceWithName(SERVICE_NAME + "-hotrod");
      final TrustStore trustStore = new TrustStore(client, SERVICE_NAME);

      final ConfigurationBuilder cfg =
         HotRodTester.baseClientConfiguration(hotRodService, true, trustStore, SERVICE_NAME);

      cfg.marshaller(new ProtoStreamMarshaller());

      remoteCacheManager = new RemoteCacheManager(cfg.build());
      initProtoSchema(remoteCacheManager);

      remoteCacheManager.administration()
         .withFlags(CacheContainerAdmin.AdminFlag.PERMANENT)
         .createCache("custom-persistent-indexed", "persistent-shared-indexed");

      cache = remoteCacheManager.getCache("custom-persistent-indexed");

      cache.put("analyzed1", new AnalyzerTestEntity("tested 123", 3));
      cache.put("analyzed2", new AnalyzerTestEntity("testing 1234", 3));
      cache.put("analyzed3", new AnalyzerTestEntity("xyz", null));
   }

   @InSequence(2)
   @Test
   public void query_data_after_put() {
      final QueryFactory queryFactory = Search.getQueryFactory(cache);
      final Query query = queryFactory
         .create("from sample_bank_account.AnalyzerTestEntity where f1:'test'");
      List<AnalyzerTestEntity> list = query.list();
      assertEquals(2, list.size());
   }

   @RunAsClient
   @InSequence(2) //must be run from the client where "oc" is installed
   @Test
   public void scale_down() {
      scalingTester.scaleDownStatefulSet(0, SERVICE_NAME, client, commandlineClient, readinessCheck);
   }

   @RunAsClient
   @InSequence(3) //must be run from the client where "oc" is installed
   @Test
   public void scale_up() {
      scalingTester.scaleUpStatefulSet(1, SERVICE_NAME, client, commandlineClient, readinessCheck);
   }

   @InSequence(4)
   @Test
   public void query_data_after_restart() {
      final QueryFactory queryFactory = Search.getQueryFactory(cache);
      final Query query = queryFactory
         .create("from sample_bank_account.AnalyzerTestEntity where f1:'test'");
      List<AnalyzerTestEntity> list = query.list();
      assertEquals(2, list.size());
   }

   public static void initProtoSchema(RemoteCacheManager remoteCacheManager) {
      // initialize server-side serialization context
      RemoteCache<String, String> metadataCache = remoteCacheManager.getCache(ProtobufMetadataManagerConstants.PROTOBUF_METADATA_CACHE_NAME);
      metadataCache.put("custom_analyzer.proto", CUSTOM_ANALYZER_PROTO_SCHEMA);
      checkSchemaErrors(metadataCache);

      // initialize client-side serialization context
      try {
         SerializationContext serCtx = ProtoStreamMarshaller.getSerializationContext(remoteCacheManager);
         MarshallerRegistration.registerMarshallers(serCtx);
         serCtx.registerProtoFiles(FileDescriptorSource.fromString("custom_analyzer.proto", CUSTOM_ANALYZER_PROTO_SCHEMA));
         serCtx.registerMarshaller(new AnalyzerTestEntityMarshaller());
      } catch (IOException e) {
         throw new AssertionError(e);
      }
   }

   /**
    * Logs the Protobuf schema errors (if any) and fails the test if there are schema errors.
    */
   public static void checkSchemaErrors(RemoteCache<String, String> metadataCache) {
      if (metadataCache.containsKey(ProtobufMetadataManagerConstants.ERRORS_KEY_SUFFIX)) {
         // The existence of this key indicates there are errors in some files
         String files = metadataCache.get(ProtobufMetadataManagerConstants.ERRORS_KEY_SUFFIX);
         for (String fname : files.split("\n")) {
            String errorKey = fname + ProtobufMetadataManagerConstants.ERRORS_KEY_SUFFIX;
            log.errorf("Found errors in Protobuf schema file: %s\n%s\n", fname, metadataCache.get(errorKey));
         }

         Assert.fail("There are errors in the following Protobuf schema files:\n" + files);
      }
   }

}
