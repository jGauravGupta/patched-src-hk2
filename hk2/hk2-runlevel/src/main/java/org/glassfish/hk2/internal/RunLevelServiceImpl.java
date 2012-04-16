/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2007-2011 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.hk2.internal;


import org.glassfish.hk2.RunLevelActivator;
import org.glassfish.hk2.RunLevelException;
import org.glassfish.hk2.RunLevelListener;
import org.glassfish.hk2.RunLevelService;
import org.glassfish.hk2.RunLevelSorter;
import org.glassfish.hk2.api.ActiveDescriptor;
import org.glassfish.hk2.api.Descriptor;
import org.glassfish.hk2.api.Filter;
import org.glassfish.hk2.api.IterableProvider;
import org.glassfish.hk2.api.ServiceHandle;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.utilities.BuilderHelper;
import org.jvnet.hk2.annotations.RunLevel;
import org.jvnet.hk2.annotations.RunLevelServiceIndicator;
import org.jvnet.hk2.annotations.Service;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The default {@link org.glassfish.hk2.RunLevelService} implementation for Hk2. See the
 * {@link org.glassfish.hk2.RunLevelService} javadoc for general details regarding this service.
 * <p/>
 * Here is a brief example of the behavior of this service:<br>
 * <p/>
 * Imagine services ServiceA, ServiceB, and ServiceC are all in the same
 * RunLevel X and the dependencies are ServiceA -> ServiceB -> ServiceC:
 * <p/>
 * <code>
 * &nbsp;@RunLevel(X)<br/>
 * &nbsp;@Service<br/>
 * public class ServiceA {<br/>
 * &nbsp;@Inject ServiceB b;<br/>
 * }<br/>
 * <br/>
 * &nbsp;@RunLevel(X)<br/>
 * &nbsp;@Service<br/>
 * public class ServiceB {<br/>
 * &nbsp;@Inject ServiceC c;<br/>
 * }<br/>
 * <br/>
 * &nbsp;@RunLevel(X)<br/>
 * &nbsp;@Service<br/>
 * public class ServiceC {<br/>
 * }<br/>
 * </code>
 * <p/>
 * When the DefaultRunLevelService is asked to proceedTo(X), the expected start
 * order is: ServiceC, ServiceB, ServiceA, and the expected shutdown order is:
 * ServiceA, ServiceB, ServiceC
 * <p/>
 * Note that no model of dependencies between services are kept in the habitat
 * to make the implementation work. Any inhabitant in RunLevel X is arbitrarily
 * picked to start with upon activation, and {@link ActiveDescriptor#create(org.glassfish.hk2.api.ServiceHandle)} is issued.
 * <p/>
 * Consider the cases of possible activation orderings:
 * <p/>
 * Case 1: A, B, then C by RLS. get ServiceA (called by RLS) Start ServiceA: get
 * ServiceB Start ServiceB: get ServiceC Start ServiceC wire ServiceC
 * PostConstruct ServiceC wire ServiceB PostConstruct ServiceB wire ServiceA
 * PostConstruct ServiceA get ServiceB (called by RLS) get ServiceC (called by
 * RLS)
 * <p/>
 * Case 2: B, C, then A by RLS. get ServiceB (called by RLS) Start ServiceB: get
 * ServiceC Start ServiceC wire ServiceC PostConstruct ServiceC wire ServiceB
 * PostConstruct ServiceB get ServiceC (called by RLS) get ServiceA (called by
 * RLS) Start ServiceA: get ServiceB wire ServiceA PostConstruct ServiceA
 * <p/>
 * Case 3: B, A, then C by RLS. get ServiceB (called by RLS) Start ServiceB: get
 * ServiceC Start ServiceC wire ServiceC PostConstruct ServiceC wire ServiceB
 * PostConstruct ServiceB get ServiceA (called by RLS) Start ServiceA: get
 * ServiceB wire ServiceA PostConstruct ServiceA get ServiceC (called by RLS)
 * <p/>
 * Case 4: C, B, then A by RLS. get ServiceC (called by RLS) Start ServiceC:
 * wire ServiceC PostConstruct ServiceC get ServiceB (called by RLS) Start
 * ServiceB: get ServiceC wire ServiceB PostConstruct ServiceB get ServiceA
 * (called by RLS) Start ServiceA: get ServiceB wire ServiceA PostConstruct
 * ServiceA get ServiceA (called by RLS)
 * <p/>
 * You can see that the order is always correct without needing to keep the
 * model of dependencies.
 * <p/>
 * ~~~
 * <p/>
 * Note that the implementation performs some level of constraint checking
 * during injection. For example,
 * <p/>
 * - It is an error to have a RunLevel-annotated service at RunLevel X to depend
 * on (i.e., be injected with) a RunLevel-annotated service at RunLevel Y when Y
 * > X.
 * <p/>
 * - It is an error to have a non-RunLevel-annotated service to depend on a
 * RunLevel-annotated service at any RunLevel.
 * <p/>
 * Note that the implementation does not handle Holder and Collection injection
 * constraint validations.
 * <p/>
 * ~~~
 * <p/>
 * The implementation will automatically proceedTo(-1) after the habitat has
 * been initialized. The value of "-1" is symbolic of the kernel run level.
 * <p/>
 * Note that all RunLevel values less than -1 will be ignored.
 * <p/>
 * ~~~
 * <p/>
 * The implementation is written to support two modes of operation, asynchronous
 * / threaded, and synchronous / single threaded. The DefaultRunLevelService
 * implementation mode is pre-configured to be synchronous. The
 * DefaultRunLevelService is thread safe.
 * <p/>
 * In the synchronous mode, calls can be made to proceedTo() to interrupt
 * processing of any currently executing proceedTo() operation. This might
 * occur: in another thread, in the {@link org.glassfish.hk2.RunLevelListener} handlers, or in a
 * {@link org.jvnet.hk2.annotations.RunLevel} annotated service's {@link javax.annotation.PostConstruct} method call.
 * <p/>
 * Note, however, that even in synchronous mode the proceedTo() operation may
 * exhibit asynchronous behavior. This is the case when the caller has two
 * threads calling proceedTo(), where the second thread is canceling the
 * operation of the first (perhaps due to timeout of a service's PostConstruct,
 * etc.). In this case, an interrupt will be sent to the first running thread to
 * cancel the previous operation, and proceedTo the run level from the second
 * thread's request. This presumes that the first thread is capable of being
 * interrupted. In such a situation, the second proceedTo() call returns
 * immediately and the first proceedTo() is interrupted to continue to the new
 * runLevel requested from the second thread's interrupt.
 * <p/>
 * For this reason, it is strongly advised that {@link InterruptedException} is
 * not swallowed by services that can be driven by the DefaultRunLevelService in
 * synchronous mode.
 * <p/>
 * proceedTo invocations from a {@link javax.annotation.PostConstruct} callback are discouraged.
 * Consider using {@link org.glassfish.hk2.RunLevelListener} instead.
 * <p/>
 * <b>Important Note:</b><br>
 * The proceedTo() method will throw unchecked exceptions if it detects that
 * it is being called reentrantly in synchronous mode. Callers should be
 * careful NOT to swallow exceptions of this type as shown in the following
 * example:
 * <p/>
 * <code>
 * try {<br/>
 * &nbsp;rls.proceedTo(x);<br>
 * } catch (Exception e) {<br>
 * &nbsp;// swallow exception<br>
 * }
 * </code>
 * <p/>
 * ~~~
 * <p/>
 * All calls to the {@link org.glassfish.hk2.RunLevelListener} happens synchronously on the
 * same thread that caused the Inhabitant to be activated. Therefore,
 * implementors of this interface should be careful and avoid calling long
 * operations.
 * <p/>
 * ~~~
 * <p/>
 *
 * @author Jeff Trent, tbeerbower
 */
@SuppressWarnings("deprecation")
@Service
//@Named(RunLevelServiceIndicator.RUNLEVEL_SERVICE_DEFAULT_NAME)
public class RunLevelServiceImpl implements RunLevelService, RunLevelActivator {
    // the initial run level
    public static final int INITIAL_RUNLEVEL = -2;

    // the default name
    public static final String DEFAULT_NAME = "default";

    // the default timeout in milliseconds (to wait for async service types)
    public static final long DEFAULT_ASYNC_WAIT = 3000;

    private static final Logger logger = Logger.getLogger(RunLevelServiceImpl.class.getName());
    private static final Level LEVEL = Level.FINE;

    private final Object lock = new Object();

    // the async mode for this instance.
    // if enabled, then all work is performed using a private
    // executor. If disabled, then almost all of the work
    // occurs on the calling thread to proceedTo(). Almost all
    // because in the event a thread calls proceedTo() while
    // another thread is already executing a proceedTo() operation,
    // the the interrupting thread will return immediately and
    // the pre-existing executing thread is interrupted to go to
    // the new run level.
    private final boolean asyncMode;

    // the private executor service if this instance is using
    // async mode.
    private final ExecutorService exec;

    // the name for this instance, defaulting to {@link DEFAULT_NAME}
    protected String name;

    // the current run level (the last one successfully achieved)
    private Integer currentRunLevel;

    // the set of recorders, one per runlevel (used as necessary, cleared when
    // shutdown)
    private final HashMap<Integer, Stack<ActiveDescriptor<?>>> recorders =
            new LinkedHashMap<Integer, Stack<ActiveDescriptor<?>>>();

    // the "active" proceedTo worker
    private Worker worker;

    // used for Async service types
    private final AsyncWaiter waiter;

    @Inject
    private ServiceLocator serviceLocator;

    @Inject
    private IterableProvider<RunLevelListener> allRunLevelListeners;

    @Inject
    private IterableProvider<RunLevelActivator> allActivators;

    @Inject
    private IterableProvider<RunLevelSorter> allSorters;

    @Inject
    private Provider<RunLevelContext> contextProvider;

    // used for eventing an {@link RunLevelListener}s
    private enum ListenerEvent {
        PROGRESS,
        CANCEL,
        ERROR,
    }

    public RunLevelServiceImpl() {
        this(false, null);
    }

    private RunLevelServiceImpl(boolean async, String name) {
        this.asyncMode = async;
        if (asyncMode) {
            // we can't use a singleThreadExecutor because a thread could become
            // "stuck"
            exec = Executors.newCachedThreadPool(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread activeThread = new RunLevelServiceThread(runnable);
                    synchronized (lock) {
                        logger.log(Level.FINE, "new thread: {0}", activeThread);
                    }
                    return activeThread;
                }
            });
            // waiter is now the responsibility of the activator
            this.waiter = null;
        } else {
            this.exec = null;
            // waiter is our responsibility
            this.waiter = new AsyncWaiter();
        }
    }

    @PostConstruct
    public void postConstruct() {
        final Named named = getClass().getAnnotation(Named.class);
        name = named == null ? RunLevelServiceIndicator.RUNLEVEL_SERVICE_DEFAULT_NAME : named.value();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "-" + System.identityHashCode(this)
                + "(" + getDescription(false) + ")";
    }

    public String getDescription(boolean extended) {
        StringBuilder b = new StringBuilder();
        b.append("curr=").append(getCurrentRunLevel()).append(", ");
        b.append("act=").append(getActivatingRunLevel()).append(", ");
        b.append("plan=").append(getPlannedRunLevel()).append(", ");
        b.append("scope=").append(getName()).append(", ");
        if (extended) {
            b.append("thrd=").append(Thread.currentThread()).append(", ");
        }
        return b.toString();
    }

    protected HashMap<Integer, Stack<ActiveDescriptor<?>>> getRecorders() {
        return recorders;
    }

    public String getName() {
        return name;
    }

    @Override
    public Integer getCurrentRunLevel() {
        return currentRunLevel;
    }

    @Override
    public Integer getPlannedRunLevel() {
        synchronized (lock) {
            return (null == worker) ? null : worker.getPlannedRunLevel();
        }
    }

    public Integer getActivatingRunLevel() {
        synchronized (lock) {
            return (null == worker) ? null : worker.getActivatingRunLevel();
        }
    }

    /**
     * Returns true if the RunLevel for the given descriptor in question should
     * be processed by this RunLevelService instance.
     *
     * @param descriptor     the descriptor
     * @param activeRunLevel the current runLevel
     * @return true if the RunLevel for the given descriptor in question should
     *         be processed by this RunLevelService instance
     */
    protected boolean accept(ActiveDescriptor<?> descriptor, int activeRunLevel) {
        Integer runLevel = getRunLevelValue(descriptor);
        if (null != runLevel) {
            if (runLevel != activeRunLevel) {
                return false;
            }
        }

        return getName().equals(getRunLevelServiceName(descriptor));
    }

    private boolean isCancelled(Worker worker) {
        synchronized (lock) {
            return (this.worker != worker);
        }
    }

    /**
     * Called after the proceedTo work is finished. This is akin to a suicide
     * act.
     *
     * @param worker the worker that was performing the work
     */
    private void finished(Worker worker) {
        synchronized (lock) {
            // ensure that the worker is the "active" one and not some zombie
            // back to life
            if (!isCancelled(worker)) {
                // it was the "trusted" worker
                this.worker = null;
            }
        }

        synchronized (this) {
            notifyAll();
        }
    }

    private void setCurrent(Worker worker, Integer current) {
        synchronized (lock) {
            // ensure that the worker is the "active" one and not some zombie
            // back to life
            if (isCancelled(worker)) {
                return;
            } else {
                this.currentRunLevel = current;
            }
        }

        // notify listeners that we progressed
        event(worker, ListenerEvent.PROGRESS, null);
    }

    protected List<Integer> getRecordersToRelease(
            HashMap<Integer, Stack<ActiveDescriptor<?>>> list, int runLevel) {
        List<Integer> qualifying = new ArrayList<Integer>();
        synchronized (lock) {
            for (Map.Entry<Integer, Stack<ActiveDescriptor<?>>> entry : recorders.entrySet()) {
                int entryKey = entry.getKey();
                if (entryKey >= runLevel) {
                    qualifying.add(entry.getKey());
                }
            }
        }

        // return in order of highest to lowest
        Collections.sort(qualifying);
        Collections.reverse(qualifying);

        return qualifying;
    }

    protected void event(Worker worker, ListenerEvent event,
                         Throwable error) {
        event(worker, event, error, false);
    }

    protected void event(Worker worker, ListenerEvent event,
                         Throwable error, boolean isHardInterrupt) {
        logger.log(LEVEL, "event {0} - " + getDescription(true), event);

        if (isCancelled(worker)) {
            logger.log(LEVEL, "Ignoring this notification!");
        } else {
            Interrupt lastInterrupt = null;
            Collection<RunLevelListener> activeListeners = getListeners();
            for (RunLevelListener listener : activeListeners) {
                try {
                    if (ListenerEvent.PROGRESS == event) {
                        listener.onProgress(this);
                    } else if (ListenerEvent.CANCEL == event) {
                        listener.onCancelled(this, currentRunLevel,
                                isHardInterrupt);
                    } else {
                        listener.onError(this, error, true);
                    }
                } catch (Interrupt interrupt) {
                    lastInterrupt = interrupt;
                } catch (Exception e) {
                    // don't percolate the exception since it may negatively
                    // impact processing
                    logger.log(Level.WARNING, "swallowing exception - "
                            + getDescription(true), new RunLevelException(e));
                }
            }

            if (null != lastInterrupt) {
                throw lastInterrupt;
            } else {
                if (null != error) {
                    logger.log(LEVEL, "swallowing error - " + error);
                }
            }
        }
    }

    protected synchronized Collection<RunLevelListener> getListeners() {
        Collection<RunLevelListener> listeners = new ArrayList<RunLevelListener>();
        for (ServiceHandle<RunLevelListener> serviceHandle : allRunLevelListeners.handleIterator()) {
            if (name.equals(getRunLevelServiceName(serviceHandle.getActiveDescriptor()))) {
                listeners.add(serviceHandle.getService());
            }
        }
        return listeners;
    }

    @Override
    public void recordActivation(ActiveDescriptor<?> descriptor) {
        Integer activeRunLevel = getRunLevelValue(descriptor);

        Stack<ActiveDescriptor<?>> activeRecorder;
        synchronized (lock) {
            activeRecorder = recorders.get(activeRunLevel);
            if (null == activeRecorder) {
                activeRecorder = new Stack<ActiveDescriptor<?>>();
                recorders.put(activeRunLevel, activeRecorder);
            }
        }
        activeRecorder.push(descriptor);
    }

    /**
     * Attempts to obtain the RunLevel value from the metadata() instead of from
     * the annotation which requires a class load. If it can't get it from the
     * metadata() it will default to load the class to obtain the RunLevel
     * value.
     *
     * @param descriptor the descriptor to get the runLevel for
     * @return the active run level
     */
    protected static Integer getRunLevelValue(Descriptor descriptor) {
        Descriptor baseDescriptor = descriptor.getBaseDescriptor();
        List<String> list = baseDescriptor.getMetadata().get(RunLevel.RUNLEVEL_VAL_META_TAG);
        return list == null ? RunLevel.RUNLEVEL_VAL_KERNAL : Integer.valueOf(list.get(0));
    }

    protected static String getRunLevelServiceName(Descriptor descriptor) {
        Descriptor baseDescriptor = descriptor.getBaseDescriptor();
        List<String> list = baseDescriptor.getMetadata().get(RunLevelServiceIndicator.RUNLEVEL_SERVICE_NAME_META_TAG);
        return list == null ? RunLevelServiceIndicator.RUNLEVEL_SERVICE_DEFAULT_NAME : list.get(0);
    }

    protected static RunLevel.Mode getRunLevelMode(Descriptor descriptor) {
        Descriptor baseDescriptor = descriptor.getBaseDescriptor();
        List<String> list = baseDescriptor.getMetadata().get(RunLevel.RUNLEVEL_MODE_META_TAG);
        return list == null ? RunLevel.Mode.VALIDATING : RunLevel.Mode.fromInt(Integer.valueOf(list.get(0)));
    }

    @Override
    public void proceedTo(int runLevel) {
        proceedTo(runLevel, false);
    }

    @Override
    public void interrupt() {
        proceedTo(null, true);
    }

    //    @Override
    public void interrupt(int runLevel) {
        proceedTo(runLevel, true);
    }

    protected void proceedTo(Integer runLevel, boolean isHardInterrupt) {
        if (null != runLevel && runLevel < RunLevel.RUNLEVEL_VAL_KERNAL) {
            throw new IllegalArgumentException();
        }

        // see if we can interrupt first
        Worker worker = this.worker;
        if (null != worker) {
            if (worker.interrupt(isHardInterrupt, runLevel)) {
                return;
            }
        }

        if (null != runLevel) {
            // if we are here then we interrupt isn't enough and we must create
            // a new worker
            synchronized (lock) {
                this.worker = worker = (asyncMode) ? new AsyncProceedToOp(
                        runLevel) : new SyncProceedToOp(runLevel);
            }

            worker.proceedTo(runLevel);
        }
    }

    @Override
    public void activate(ActiveDescriptor<?> descriptor) {
        serviceLocator.getServiceHandle(descriptor).getService();
    }

    @Override
    public void deactivate(ActiveDescriptor<?> descriptor) {
        contextProvider.get().deactivate(descriptor);
    }

    @Override
    public void awaitCompletion()
            throws InterruptedException, ExecutionException, TimeoutException {
        if (null != waiter) {
            long start = System.currentTimeMillis();
            logger.log(Level.FINER, "awaiting completion");
            waiter.waitForDone();
            logger.log(Level.FINER, "finished awaiting completion - {0} ms", System.currentTimeMillis() - start);
        }
    }

    @Override
    public void awaitCompletion(long timeout, TimeUnit unit)
            throws InterruptedException, TimeoutException, ExecutionException {
        if (null != waiter) {
            long start = System.currentTimeMillis();
            logger.log(Level.FINER, "awaiting completion");
            boolean done = waiter.waitForDone(timeout, unit);
            logger.log(Level.FINER, "finished awaiting completion - {0} ms; done = {1}",
                    new Object[]{System.currentTimeMillis() - start, done});
        }
    }

    /**
     * Obtains the "best" InhabitantActivator, first looking up out of the
     * habitat any service registered under the same name as this, then
     * defaulting to the first one registered by type alone, followed by
     * ourself.
     *
     * @return an InhabitantActivator, defaulting to this
     */
    protected synchronized RunLevelActivator getActivator() {
        Collection<RunLevelActivator> activators = new ArrayList<RunLevelActivator>();
        for (ServiceHandle<RunLevelActivator> serviceHandle : allActivators.handleIterator()) {
            if (name.equals(getRunLevelServiceName(serviceHandle.getActiveDescriptor()))) {
                activators.add(serviceHandle.getService());
            }
        }
        return (activators.isEmpty()) ? this : activators.iterator().next();
    }

    protected synchronized RunLevelSorter getSorter() {
        Collection<RunLevelSorter> sorters = new ArrayList<RunLevelSorter>();
        for (ServiceHandle<RunLevelSorter> serviceHandle : allSorters.handleIterator()) {
            if (name.equals(getRunLevelServiceName(serviceHandle.getActiveDescriptor()))) {
                sorters.add(serviceHandle.getService());
            }
        }
        return (sorters.isEmpty()) ? null : sorters.iterator().next();
    }

    private abstract class Worker implements Runnable {
        // the target runLevel we want to achieve
        protected volatile Integer planned;

        // the active run level attempting to be activated
        private Integer activeRunLevel;

        // tracks the direction of any active proceedTo worker
        protected Boolean upSide;

        // records whether a cancel was actually an hard interrupt
        protected Boolean isHardInterrupt;

        protected Worker(int runLevel) {
            this.planned = runLevel;
        }

        public Integer getPlannedRunLevel() {
            return planned;
        }

        public Integer getActivatingRunLevel() {
            return activeRunLevel;
        }

        /**
         * Checks to see if this worker has been interrupted, and will abort if
         * it finds it has been.
         *
         * @param e          any error encountered during the nested proceedTo
         *                   operation; may be null
         * @param descriptor the inhabitant that was being activated / released during
         *                   the operation; may be null
         * @param isHard     true when this is a "hard" interrupt originating from an
         *                   interrupt() call, false when it was from a "soft"
         *                   interrupt involving a new proceedTo(), null when its
         *                   unknown altogether
         */
        protected void checkInterrupt(Exception e, ActiveDescriptor<?> descriptor,
                                      Boolean isHard) {
            if (null != e) {
                boolean isHardInterrupt = isHardInterrupt(isHard, e);
                if (isHardInterrupt) {
                    event(this, ListenerEvent.CANCEL, e, isHardInterrupt);
                } else {
                    event(this, ListenerEvent.ERROR, e, isHardInterrupt);
                }
            }
        }

        /**
         * Attempts to interrupt processing to go to a new runLevel.
         *
         * @param isHard   if true, this was based on an explicit call to interrupt;
         *                 false otherwise. The latter is the case for a new
         *                 proceedTo() causing a cancel in order to proceedTo a new
         *                 runLevel.
         * @param runLevel optionally, the revised runLevel to proceedTo
         * @return true, if its possible to go to the new runLevel; note that
         *         implementation may handle the interrupt by other means (i.e.,
         *         throwing an InterruptException for the synchronous case)
         */
        public abstract boolean interrupt(boolean isHard, Integer runLevel);

        /**
         * Called after initialization to run the proceedTo operation
         *
         * @param runLevel the runLevel to proceedTo
         */
        public abstract void proceedTo(int runLevel);

        /**
         * Core control logic.
         */
        @Override
        public void run() {
            logger.log(LEVEL, "proceedTo({0}) - " + getDescription(true),
                    planned);

            upSide = null;

            if (null != planned) {
                int current = (null == getCurrentRunLevel()) ? INITIAL_RUNLEVEL
                        : getCurrentRunLevel();
                if (planned > current) {
                    upSide = true;

                    int rl = current + 1;
                    while (rl <= planned) {
                        upActiveRecorder(rl);
                        rl++;
                    }
                } else if (planned < current) {
                    upSide = false;

                    // start things off we a notification of the current
                    // runLevel
                    setCurrent(this, current);

                    down(current);
                } else { // planned == current
                    upSide = false;

                    // force closure of any orphaned higher RunLevel services
                    down(current + 1);
                }
            }

            finished(this);
        }

        private void down(int start) {
            int rl = start;
            while (rl > planned) {
                downActiveRecorder(rl);
                rl--;
            }
        }

        private void upActiveRecorder(int runLevel) {
            activeRunLevel = runLevel;

            // create demand for RunLevel (runLevel) components
            activateRunLevel();

            // don't set current until we've actually reached it
            setCurrent(this, runLevel);
        }

        private void activateRunLevel() {
            List<ActiveDescriptor<?>> activations = new ArrayList<ActiveDescriptor<?>>();

            final Filter filter = BuilderHelper.createContractFilter(RunLevel.class.getName());

            // TODO: we could cache this in top-level proceedTo()
            List<ActiveDescriptor<?>> descriptors =
                    serviceLocator.getDescriptors(filter);

            for (ActiveDescriptor<?> descriptor : descriptors) {
                if (accept(descriptor, activeRunLevel)) {
                    activations.add(descriptor);
                }
            }

            if (!activations.isEmpty()) {
                if (logger.isLoggable(LEVEL)) {
                    logger.log(LEVEL, "sorting {0}", activations);
                }

                RunLevelSorter sorter = getSorter();
                if (sorter != null) {
                    sorter.sort(activations);
                }

                RunLevelActivator ia = getActivator();

                // clear out the old set of watches
                if (null != waiter) {
                    waiter.clear();
                }

                for (ActiveDescriptor<?> descriptor : activations) {
                    if (logger.isLoggable(LEVEL)) {
                        logger.log(LEVEL, "activating {0} - "
                                + getDescription(true), descriptor);
                    }

                    ServiceHandle<?> serviceHandle = serviceLocator.getServiceHandle(descriptor);
                    try {
                        ia.activate(descriptor);

                        if (null != waiter) {
                            waiter.watchIfNecessary(serviceHandle);
                        }

                        // an escape hatch if we've been interrupted in some way
                        checkInterrupt(null, descriptor, null);
                    } catch (Exception e) {
                        checkInterrupt(e, descriptor, null);
                    }
                }

                try {
                    ia.awaitCompletion(DEFAULT_ASYNC_WAIT, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    ActiveDescriptor<?> descriptor = (null == waiter) ? null : waiter.getLastDescriptorWorkingOn();
                    checkInterrupt(e, descriptor, null);
                }
            }
        }

        protected void downActiveRecorder(int runLevel) {
            activeRunLevel = runLevel;

            // release stuff
            deactivateRunLevel(runLevel);

            // don't set current until we've actually reached it
            setCurrent(this, activeRunLevel = runLevel - 1);
        }

        private void deactivateRunLevel(int runLevel) {
            List<Integer> downRecorders = getRecordersToRelease(recorders,
                    runLevel);
            for (int current : downRecorders) {
                Stack<ActiveDescriptor<?>> downRecorder;
                synchronized (lock) {
                    downRecorder = recorders.get(current);
                }

                if (null != downRecorder) {
                    // Causes release of the entire activationSet. Release
                    // occurs in the inverse
                    // order of the recordings. So A->B->C will have startUp
                    // ordering be (C,B,A)
                    // because of dependencies. The shutdown ordering will b
                    // (A,B,C).

                    RunLevelActivator ia = getActivator();

                    ActiveDescriptor<?> descriptor;
                    while (!downRecorder.isEmpty()) {
                        descriptor = downRecorder.pop();
                        if (logger.isLoggable(LEVEL)) {
                            logger.log(LEVEL, "releasing {0} - "
                                    + getDescription(true), descriptor);
                        }

                        try {
                            ia.deactivate(descriptor);
                            // assert(!i.isActive()); <- this might happen
                            // asynchronously
                            checkInterrupt(null, descriptor, null);
                        } catch (Exception e) {
                            checkInterrupt(e, descriptor, null);
                        }
                    }

                    try {
                        ia.awaitCompletion();
                    } catch (Exception e) {
                        checkInterrupt(e, null, null);
                    }
                }
            }
        }

        protected boolean isHardInterrupt(Boolean isHard, Throwable e) {
            if (null != isHard) {
                return isHard;
            }

            return (null == isHardInterrupt) ? false : isHardInterrupt;
        }
    }

    /**
     * Sync worker
     */
    private class SyncProceedToOp extends Worker {
        // record the thread performing the operation
        private final Thread activeThread = Thread.currentThread();

        // the next planned runLevel (after interrupt)
        protected Integer nextPlannedAfterInterrupt;

        // records whether a cancel event was issued
        private boolean cancelIssued;

        private SyncProceedToOp(int runLevel) {
            super(runLevel);
        }

        /**
         * Interrupts are always handled in the synchronous case either by
         * popping the stack for the reentrant call on same thread, or by
         * sending a cancel event to the active thread doing the proceedTo()
         * call.
         */
        @Override
        public boolean interrupt(boolean isHard, Integer runLevel) {
            Thread ourThread = Thread.currentThread();

            synchronized (lock) {
                Integer planned = getPlannedRunLevel();
                if (!isHard && null != planned && planned.equals(runLevel)) {
                    return true; // short circuit
                }

                nextPlannedAfterInterrupt = runLevel;

                if (ourThread == activeThread) {
                    checkInterrupt(null, null, isHard);
                } else {
                    // must interrupt another thread to do the new proceedTo().
                    // Note how this thread exhibits async behavior in this
                    // case.
                    // The cancel notification will happen on the other thread
                    logger.log(LEVEL, "Interrupting thread {0} - "
                            + getDescription(true), activeThread);

                    this.isHardInterrupt = isHard;
                    activeThread.interrupt();
                }
            }

            return true;
        }

        @Override
        public void proceedTo(int runLevel) {
            synchronized (lock) {
                planned = runLevel;
                nextPlannedAfterInterrupt = null;
                cancelIssued = false;
                isHardInterrupt = null;
            }

            try {
                run();
            } catch (Exception e) {
                handleInterruptException(e);
            }
        }

        @Override
        protected void checkInterrupt(Exception e, ActiveDescriptor<?> descriptor,
                                      Boolean isHard) {
            synchronized (lock) {
                boolean cancelled = isCancelled(this);
                if (cancelled || null != nextPlannedAfterInterrupt) {
                    if (!cancelled
                            && canUpdateProceedTo(nextPlannedAfterInterrupt)) {
                        planned = nextPlannedAfterInterrupt;
                        nextPlannedAfterInterrupt = null;
                        e = null;
                    } else {
                        // send cancel event, but only one time
                        if (!cancelIssued) {
                            cancelIssued = true;
                            boolean wasHardInterrupt = isHardInterrupt(isHard,
                                    e);
                            isHardInterrupt = null;
                            event(this, ListenerEvent.CANCEL,
                                    null,
                                    wasHardInterrupt);
                        }

                        // pop stack to last proceedTo()
                        throw new Interrupt();
                    }
                }
            }
            super.checkInterrupt(e, descriptor, isHard);
        }

        private boolean canUpdateProceedTo(Integer proposed) {
            if (null != upSide) {
                Integer planned = getPlannedRunLevel();
                Integer active = getActivatingRunLevel();
                if (null != planned && null != active && null != proposed) {
                    if (upSide && proposed > active) {
                        return true;
                    } else if (!upSide && proposed < active) {
                        return true;
                    }
                }
            }

            return false;
        }

        private void handleInterruptException(Exception e) {
            logger.log(LEVEL, "Interrupt caught - " + getDescription(true), e);

            Thread currentThread = Thread.currentThread();

            // we want to handle the new proceedTo if interrupted by another
            // thread,
            // otherwise we fall out since we are not the owning thread.
            Integer next = null;
            if (activeThread == currentThread) {
                next = nextPlannedAfterInterrupt;
            }

            if (null != next) {
                proceedTo(next);
            } else {
                // RLS must continue / fall out
                logger.log(LEVEL, "swallowing exception - "
                        + getDescription(true), new RunLevelException(e));
            }
        }

    }

    /**
     * Async worker
     */
    private class AsyncProceedToOp extends Worker implements Runnable {
        // record the future for the operation
        private Future<?> activeFuture;

        private AsyncProceedToOp(int runLevel) {
            super(runLevel);
        }

        /**
         * Interrupts are never handled in the asynchronous case.
         * <p/>
         * Here, we just kill the worker, and expect a new one to form.
         */
        @Override
        public boolean interrupt(boolean isHard, Integer runLevel) {
            boolean haveFuture;
            synchronized (lock) {
                haveFuture = (null != activeFuture);
                if (haveFuture) {
                    // cancel previous, but down hit thread with interrupt
                    activeFuture.cancel(false);
                    activeFuture = null;
                }
            }

            if (haveFuture) {
                event(this, ListenerEvent.CANCEL, null, isHard);
            }

            return false;
        }

        @Override
        public void run() {
            super.run();
            synchronized (lock) {
                activeFuture = null;
                isHardInterrupt = null;
            }
        }

        @Override
        public void proceedTo(int runLevel) {
            assert (null == activeFuture);
            activeFuture = exec.submit(this);
        }

        @Override
        protected void checkInterrupt(Exception e, ActiveDescriptor<?> descriptor,
                                      Boolean isHard) {
            if (isCancelled(this)) {
                throw new Interrupt();
            }
            super.checkInterrupt(e, descriptor, isHard);
        }
    }

    private static class RunLevelServiceThread extends Thread {
        private RunLevelServiceThread(Runnable r) {
            super(r);
            setDaemon(true);
            setName(getClass().getSimpleName() + "-"
                    + System.currentTimeMillis());
        }
    }

    @SuppressWarnings("serial")
    public static class Interrupt extends RuntimeException {
        private Interrupt() {
        }
    }
}
