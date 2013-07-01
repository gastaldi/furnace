/*
 * Copyright 2013 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.jboss.forge.furnace;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jboss.forge.furnace.addons.AddonRegistry;
import org.jboss.forge.furnace.addons.AddonView;
import org.jboss.forge.furnace.impl.AddonLifecycleManager;
import org.jboss.forge.furnace.impl.AddonRegistryImpl;
import org.jboss.forge.furnace.impl.AddonRepositoryImpl;
import org.jboss.forge.furnace.impl.ImmutableAddonRepository;
import org.jboss.forge.furnace.lock.LockManager;
import org.jboss.forge.furnace.repositories.AddonRepository;
import org.jboss.forge.furnace.repositories.AddonRepositoryMode;
import org.jboss.forge.furnace.spi.ContainerLifecycleListener;
import org.jboss.forge.furnace.spi.ListenerRegistration;
import org.jboss.forge.furnace.util.Assert;
import org.jboss.forge.furnace.util.Sets;
import org.jboss.forge.furnace.versions.Version;
import org.jboss.modules.Module;
import org.jboss.modules.log.StreamModuleLogger;

/**
 * @author <a href="mailto:lincolnbaxter@gmail.com">Lincoln Baxter, III</a>
 */
public class FurnaceImpl implements Furnace
{
   private static Logger logger = Logger.getLogger(FurnaceImpl.class.getName());

   private volatile boolean alive = false;
   private volatile ContainerStatus status = ContainerStatus.STOPPED;

   private boolean serverMode = true;
   private AddonLifecycleManager manager;
   private List<ContainerLifecycleListener> registeredListeners = new ArrayList<ContainerLifecycleListener>();
   private List<ListenerRegistration<ContainerLifecycleListener>> loadedListenerRegistrations = new ArrayList<ListenerRegistration<ContainerLifecycleListener>>();

   private ClassLoader loader;

   private List<AddonRepository> repositories = new ArrayList<AddonRepository>();
   private Map<AddonRepository, Integer> lastRepoVersionSeen = new HashMap<AddonRepository, Integer>();

   private final LockManager lock = new LockManagerImpl();
   private final Set<AddonView> views = Sets.getConcurrentSet();

   private String[] args;

   public FurnaceImpl()
   {
      if (!AddonRepositoryImpl.hasRuntimeAPIVersion())
         logger.warning("Could not detect Furnace runtime version - " +
                  "loading all addons, but failures may occur if versions are not compatible.");

      manager = new AddonLifecycleManager(this);
   }

   @Override
   public LockManager getLockManager()
   {
      return lock;
   }

   @Override
   public ClassLoader getRuntimeClassLoader()
   {
      return loader;
   }

   public Furnace enableLogging()
   {
      assertNotAlive();
      Module.setModuleLogger(new StreamModuleLogger(System.err));
      return this;
   }

   @Override
   public Furnace startAsync()
   {
      return startAsync(Thread.currentThread().getContextClassLoader());
   }

   @Override
   public Furnace startAsync(final ClassLoader loader)
   {
      new Thread()
      {
         @Override
         public void run()
         {
            Thread.currentThread().setName("Furnace Container " + FurnaceImpl.this);
            FurnaceImpl.this.start(loader);
         };
      }.start();

      return this;
   }

   @Override
   public Furnace start()
   {
      return start(Thread.currentThread().getContextClassLoader());
   }

   @Override
   public Furnace start(ClassLoader loader)
   {
      assertNotAlive();
      alive = true;

      this.loader = loader;

      for (ContainerLifecycleListener listener : ServiceLoader.load(ContainerLifecycleListener.class, loader))
      {
         ListenerRegistration<ContainerLifecycleListener> registration = addContainerLifecycleListener(listener);
         loadedListenerRegistrations.add(registration);
      }

      fireBeforeContainerStartedEvent(loader);
      status = ContainerStatus.STARTED;

      try
      {
         getAddonRegistry();
         do
         {
            boolean dirty = false;
            if (!manager.isStartingAddons(views))
            {
               for (AddonRepository repository : repositories)
               {
                  int repoVersion = repository.getVersion();
                  if (repoVersion > lastRepoVersionSeen.get(repository))
                  {
                     logger.log(Level.INFO, "Detected changes in repository [" + repository + "].");
                     lastRepoVersionSeen.put(repository, repoVersion);
                     dirty = true;
                  }
               }

               if (dirty)
               {
                  try
                  {
                     manager.forceUpdate(views);
                  }
                  catch (Exception e)
                  {
                     logger.log(Level.SEVERE, "Error occurred.", e);
                  }
               }
            }
            Thread.sleep(100);
         }
         while (alive && serverMode);

         while (alive && manager.isStartingAddons(views))
         {
            Thread.sleep(100);
         }
      }
      catch (Exception e)
      {
         logger.log(Level.SEVERE, "Error occurred.", e);
      }
      finally
      {
         fireBeforeContainerStoppedEvent(loader);
         status = ContainerStatus.STOPPED;
         manager.stopAll();
      }

      fireAfterContainerStoppedEvent(loader);
      for (ListenerRegistration<ContainerLifecycleListener> registation : loadedListenerRegistrations)
      {
         registation.removeListener();
      }
      return this;
   }

   private void fireBeforeContainerStartedEvent(ClassLoader loader)
   {
      for (ContainerLifecycleListener listener : registeredListeners)
      {
         listener.beforeStart(this);
      }
   }

   private void fireBeforeContainerStoppedEvent(ClassLoader loader)
   {
      for (ContainerLifecycleListener listener : registeredListeners)
      {
         listener.beforeStop(this);
      }
   }

   private void fireAfterContainerStoppedEvent(ClassLoader loader)
   {
      for (ContainerLifecycleListener listener : registeredListeners)
      {
         listener.afterStop(this);
      }
   }

   @Override
   public Furnace stop()
   {
      alive = false;
      return this;
   }

   @Override
   public void setArgs(String[] args)
   {
      assertNotAlive();
      this.args = args;
   }

   @Override
   public String[] getArgs()
   {
      return args;
   }

   @Override
   public Furnace setServerMode(boolean server)
   {
      assertNotAlive();
      this.serverMode = server;
      return this;
   }

   @Override
   public AddonRegistry getAddonRegistry(AddonRepository... repositories)
   {
      assertIsAlive();

      AddonRegistryImpl result = null;
      if (repositories == null || repositories.length == 0)
         result = new AddonRegistryImpl(lock, manager, getRepositories());
      else
         result = new AddonRegistryImpl(lock, manager, Arrays.asList(repositories));

      if (!views.contains(result))
         views.add(result);

      return result;
   }

   @Override
   public void disposeAddonRegistry(AddonRegistry registry)
   {
      assertIsAlive();

      if (getAddonRegistry().equals(registry))
         throw new IllegalArgumentException(
                  "Cannot dispose the root AddonRegistry. Call .stop() instead.");

      if (!views.contains(registry))
         throw new IllegalArgumentException("The given AddonRegistry does not belong to this Furnace instance.");

      views.remove(registry);
   }

   @Override
   public Version getVersion()
   {
      return AddonRepositoryImpl.getRuntimeAPIVersion() == null ? null : AddonRepositoryImpl.getRuntimeAPIVersion();
   }

   @Override
   public ListenerRegistration<ContainerLifecycleListener> addContainerLifecycleListener(
            final ContainerLifecycleListener listener)
   {
      registeredListeners.add(listener);
      return new ListenerRegistration<ContainerLifecycleListener>()
      {
         @Override
         public ContainerLifecycleListener removeListener()
         {
            registeredListeners.remove(listener);
            return listener;
         }
      };
   }

   @Override
   public List<AddonRepository> getRepositories()
   {
      return Collections.unmodifiableList(repositories);
   }

   @Override
   public AddonRepository addRepository(AddonRepositoryMode mode, File directory)
   {
      assertNotAlive();

      Assert.notNull(mode, "Addon repository mode must not be null.");
      Assert.notNull(mode, "Addon repository directory must not be null.");

      for (AddonRepository registeredRepo : repositories)
      {
         if (registeredRepo.getRootDirectory().equals(directory))
         {
            throw new IllegalArgumentException("There is already a repository defined with this path: " + directory);
         }
      }
      AddonRepository repository = AddonRepositoryImpl.forDirectory(this, directory);

      if (mode.isImmutable())
         repository = new ImmutableAddonRepository(repository);

      this.repositories.add(repository);
      lastRepoVersionSeen.put(repository, 0);

      return repository;
   }

   public void assertIsAlive()
   {
      if (!alive)
         throw new IllegalStateException(
                  "Cannot access this method until Furnace is running. Call .start() or .startAsync() first.");
   }

   public void assertNotAlive()
   {
      if (alive)
         throw new IllegalStateException("Cannot modify a running Furnace instance. Call .stop() first.");
   }

   @Override
   public ContainerStatus getStatus()
   {
      if (!alive)
         return ContainerStatus.STOPPED;

      boolean startingAddons = manager.isStartingAddons(views);
      return startingAddons ? ContainerStatus.STARTING : status;
   }

   public List<ContainerLifecycleListener> getRegisteredListeners()
   {
      return Collections.unmodifiableList(registeredListeners);
   }

   public AddonLifecycleManager getAddonLifecycleManager()
   {
      return manager;
   }
}
