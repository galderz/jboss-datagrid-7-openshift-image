package org.infinispan.online.service.utils;

import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

public class DataGrid {

   private DataGrid() {
   }

   public static Function<ConfigurationBuilder, Void> withRemoteCacheManager(
      Consumer<RemoteCacheManager> fun) {

      CachingFunction<ConfigurationBuilder, Void> createFun =
         createRemoteCacheManager();

      CachingFunction<Void, Throwable> launderFun =
         new CachingFunction<Void, Throwable>() {
            @Override
            public Throwable apply(Void x) {
               try {
                  fun.accept(getRemoteCacheManager());
                  return null;
               } catch (Throwable t) {
                  return t;
               }
            }
         };

      CachingFunction<Throwable, Void> destroyFun =
         destroyRemoteCacheManager();

      return createFun.andThen(launderFun).andThen(destroyFun);
   }

   private static CachingFunction<Throwable, Void> destroyRemoteCacheManager() {
      return new CachingFunction<Throwable, Void>() {
         @Override
         public Void apply(Throwable throwable) {
            safelyStopRemoteCacheManager().apply(getRemoteCacheManager());

            if (throwable != null)
               throw new AssertionError(throwable);

            return null;
         }
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

   private static CachingFunction<ConfigurationBuilder, Void> createRemoteCacheManager() {
      return new CachingFunction<ConfigurationBuilder, Void>() {
         @Override
         public Void apply(ConfigurationBuilder cfg) {
            setRemoteCacheManager(new RemoteCacheManager(cfg.build()));
            return null;
         }
      };
   }

   private static abstract class CachingFunction<T, R> implements Function<T, R> {
      RemoteCacheManager remoteCacheManager;

      RemoteCacheManager getRemoteCacheManager() {
         return remoteCacheManager;
      }

      void setRemoteCacheManager(RemoteCacheManager remoteCacheManager) {
         this.remoteCacheManager = remoteCacheManager;
      }

      <V> Function<T, V> andThen(CachingFunction<? super R, ? extends V> after) {
         Objects.requireNonNull(after);
         after.setRemoteCacheManager(remoteCacheManager);
         return (T t) -> after.apply(apply(t));
      }
   }

}
