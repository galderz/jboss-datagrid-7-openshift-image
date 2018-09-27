package org.infinispan.online.service.utils;

import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.commons.logging.Log;
import org.infinispan.commons.logging.LogFactory;
import org.infinispan.commons.util.Either;

import java.util.function.Function;

public class DataGrid {

   private static final Log log = LogFactory.getLog(DataGrid.class);

   private DataGrid() {
   }

   public static Function<ConfigurationBuilder, Void> withRemoteCacheManager(
      Function<RemoteCacheManager, RemoteCacheManager> fun) {

      Function<ConfigurationBuilder, RemoteCacheManager> createFun =
         createRemoteCacheManager();

      Function<RemoteCacheManager, Either<Object[], RemoteCacheManager>> launderFun =
         remote -> {
            try {
               return Either.newRight(fun.apply(remote));
            } catch (Throwable t) {
               return Either.newLeft(new Object[]{t, remote});
            }
         };

      Function<Either<Object[], RemoteCacheManager>, Void> destroyFun =
         destroyRemoteCacheManager();

      return createFun.andThen(launderFun).andThen(destroyFun);
   }

   private static Function<Either<Object[], RemoteCacheManager>, Void> destroyRemoteCacheManager() {
      return result -> {
         switch (result.type()) {
            case LEFT:
               final Throwable throwable = (Throwable) result.left()[0];
               safelyStopRemoteCacheManager().apply((RemoteCacheManager) result.left()[1]);
               throw new AssertionError(throwable);
            case RIGHT:
               safelyStopRemoteCacheManager().apply(result.right());
               break;
         }

         return null;
      };
   }

   private static Function<RemoteCacheManager, Void> safelyStopRemoteCacheManager() {
      return remote -> {
         try {
            remote.stop();
         } catch (Throwable t) {
            // ignore
         }

         return null;
      };
   }

   private static Function<ConfigurationBuilder, RemoteCacheManager> createRemoteCacheManager() {
      return cfg -> new RemoteCacheManager(cfg.build());
   }

}
