package org.infinispan.online.service.utils;

import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.commons.logging.Log;
import org.infinispan.commons.logging.LogFactory;

import java.util.Objects;
import java.util.function.Consumer;

public final class DataGrid {

   private static final Log log = LogFactory.getLog(DataGrid.class);

   private DataGrid() {
   }

   public static Function<ConfigurationBuilder, RemoteCacheManager> createRemoteCacheManager() {
      return new Function<ConfigurationBuilder, RemoteCacheManager>() {

         RemoteCacheManager remoteCacheManager;

         @Override
         public RemoteCacheManager apply(ConfigurationBuilder cfg) {
            System.out.println("Called create");
            this.remoteCacheManager = new RemoteCacheManager(cfg.build());
            return this.remoteCacheManager;
         }

         @Override
         public Consumer<ConfigurationBuilder> andThenConsume(Consumer<? super RemoteCacheManager> after) {
            Objects.requireNonNull(after);

            return cfg -> {
               try {
                  after.accept(apply(cfg));
               } catch (Throwable t) {
                 log.error("Unexpected exception", t);
                 throw t;
               } finally {
                  try {
                     log.info("Stopping remote cache manager");
                     this.remoteCacheManager.stop();
                     log.info("Stopped remote cache manager");
                  } catch (Throwable throwable) {
                     // ignore
                  }
               }
            };
         }

      };
   }

   public interface Function<T, R> extends java.util.function.Function<T, R> {

      default Consumer<T> andThenConsume(Consumer<? super R> after) {
         Objects.requireNonNull(after);

         return (T t) -> {
            after.accept(apply(t));
         };
      }

   }

}
