package com.greytest.service;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import javax.sql.DataSource;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Chỉ gắn trong benchmark: lưu interval, CPU thread và correlation, không lưu SQL/source/prompt. */
final class CriticalPathProfiler {
    interface Call<T> { T get() throws Throwable; }
    record Span(long id, long parent, String name, long thread, String stage, int batch, String targets,
                long startNanos, long endNanos, long cpuNanos, boolean failed) { }
    static final class Run {
        final String id = UUID.randomUUID().toString();
        final String workload;
        final long projectId, updateId, started = System.nanoTime();
        final ConcurrentLinkedQueue<Span> events = new ConcurrentLinkedQueue<>();
        Run(String workload, long projectId, long updateId) {
            this.workload = workload; this.projectId = projectId; this.updateId = updateId;
        }
        List<Span> spans() { return List.copyOf(events); }
    }
    private record Context(Run run, long parent, String stage, int batch, String targets) { }
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private static final AtomicLong IDS = new AtomicLong();
    private static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();
    private record Transaction(Context context, long started) { }
    private static final ThreadLocal<java.util.IdentityHashMap<Object, Transaction>> TRANSACTIONS =
            ThreadLocal.withInitial(java.util.IdentityHashMap::new);

    static <T> T bind(Run run, Call<T> call) throws Throwable {
        return within(new Context(run, 0, "ANALYZE", 0, "[]"), call);
    }
    private static <T> T within(Context value, Call<T> call) throws Throwable {
        Context previous = CURRENT.get();
        CURRENT.set(value);
        try { return call.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    static <T> T unchecked(String name, Call<T> call) {
        try { return observe(name, call); }
        catch (RuntimeException | Error e) { throw e; }
        catch (Throwable e) { throw new IllegalStateException(e); }
    }
    static <T> T observe(String name, Call<T> call) throws Throwable {
        Context context = CURRENT.get();
        if (context == null) return call.get();
        long id = IDS.incrementAndGet(), started = System.nanoTime(), cpu = cpu();
        boolean failed = true;
        try {
            T result = within(new Context(context.run, id, context.stage, context.batch, context.targets), call);
            failed = false;
            return result;
        } finally {
            long endedCpu = cpu();
            context.run.events.add(new Span(id, context.parent, name, Thread.currentThread().getId(),
                    context.stage, context.batch, context.targets, started - context.run.started,
                    System.nanoTime() - context.run.started, cpu < 0 || endedCpu < 0 ? -1 : endedCpu - cpu, failed));
        }
    }
    private static long cpu() {
        return CPU.isCurrentThreadCpuTimeSupported() && CPU.isThreadCpuTimeEnabled() ? CPU.getCurrentThreadCpuTime() : -1;
    }
    static Function<Object, Object> batchWorker(List<?> inputs, Function<Object, Object> worker) {
        Context captured = CURRENT.get();
        long ready = System.nanoTime();
        return input -> {
            if (captured == null) return worker.apply(input);
            int batch = inputs.indexOf(input) + 1;
            Context context = new Context(captured.run, captured.parent, captured.stage, batch, String.valueOf(input));
            try {
                return within(context, () -> {
                    long now = System.nanoTime();
                    context.run.events.add(new Span(IDS.incrementAndGet(), context.parent, "batch.readyToStart", Thread.currentThread().getId(),
                            context.stage, batch, context.targets, ready - context.run.started, now - context.run.started, -1, false));
                    return observe("batch.worker", () -> worker.apply(input));
                });
            } catch (RuntimeException | Error e) { throw e; }
            catch (Throwable e) { throw new IllegalStateException(e); }
        };
    }

    private static final Set<String> SERVICES = Set.of("SourceUpdateService", "SourceDiffService", "ImpactAnalysisService",
            "JavaParserHelper", "GenerationContextBuilder", "PromptManager", "GenerationResponseParser", "AIAgentService",
            "GenerationFingerprintService", "IncrementalCheckpointCoordinator", "GenerationCheckpointStore",
            "PersistentLlmResponseStore", "IncrementalGenerationService", "LlmBatchExecutor");

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean static BeanPostProcessor criticalPathObserver() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof DataSource) return jdbcProxy(bean, DataSource.class);
                    String simple = org.springframework.aop.support.AopUtils.getTargetClass(bean).getSimpleName();
                    if (!SERVICES.contains(simple) && !name.endsWith("Repository")
                            && !(bean instanceof org.springframework.transaction.PlatformTransactionManager)) return bean;
                    MethodInterceptor observer = invocation -> {
                        Context context = CURRENT.get();
                        if (context == null) return invocation.proceed();
                        String method = invocation.getMethod().getName();
                        Object[] args = invocation.getArguments();
                        if (simple.equals("IncrementalCheckpointCoordinator") && method.equals("beginStage"))
                            CURRENT.set(new Context(context.run, context.parent, String.valueOf(args[1]), 0, "[]"));
                        if (simple.equals("LlmBatchExecutor") && method.equals("map") && args.length == 4) {
                            @SuppressWarnings("unchecked") var worker = (Function<Object, Object>) args[2];
                            args[2] = batchWorker((List<?>) args[0], worker);
                        }
                        if (!(bean instanceof org.springframework.transaction.PlatformTransactionManager))
                            return observe(name + "." + method, invocation::proceed);
                        boolean failed = true;
                        try {
                            Object result = observe(name + "." + method, invocation::proceed);
                            if (method.equals("getTransaction") && result instanceof org.springframework.transaction.TransactionStatus status
                                    && status.isNewTransaction()) TRANSACTIONS.get().put(result, new Transaction(CURRENT.get(), System.nanoTime()));
                            failed = false;
                            return result;
                        } finally {
                            if ((method.equals("commit") || method.equals("rollback")) && args.length == 1) {
                                Transaction tx = TRANSACTIONS.get().remove(args[0]);
                                if (tx != null) {
                                    Context origin = tx.context;
                                    origin.run.events.add(new Span(IDS.incrementAndGet(), origin.parent, "transaction.hold", Thread.currentThread().getId(),
                                            origin.stage, origin.batch, origin.targets, tx.started - origin.run.started,
                                            System.nanoTime() - origin.run.started, -1, failed));
                                }
                                if (TRANSACTIONS.get().isEmpty()) TRANSACTIONS.remove();
                            }
                        }
                    };
                    if (bean instanceof Advised advised) { advised.addAdvice(0, observer); return bean; }
                    ProxyFactory factory = new ProxyFactory(bean);
                    factory.setProxyTargetClass(!(bean instanceof org.springframework.transaction.PlatformTransactionManager));
                    factory.addAdvice(observer);
                    return factory.getProxy();
                }
            };
        }
    }

    private static Object jdbcProxy(Object target, Class<?> contract) {
        return Proxy.newProxyInstance(CriticalPathProfiler.class.getClassLoader(), new Class<?>[] { contract }, (proxy, method, args) -> {
            Call<Object> invoke = () -> {
                try { return method.invoke(target, args); }
                catch (InvocationTargetException e) { throw e.getCause(); }
            };
            String name = method.getName();
            Object value = name.startsWith("execute") || name.equals("commit") || name.equals("rollback") || name.equals("getConnection")
                    ? observe("jdbc." + name, invoke) : invoke.get();
            if (value instanceof Connection) return jdbcProxy(value, Connection.class);
            if (value instanceof java.sql.CallableStatement) return jdbcProxy(value, java.sql.CallableStatement.class);
            if (value instanceof java.sql.PreparedStatement) return jdbcProxy(value, java.sql.PreparedStatement.class);
            if (value instanceof Statement) return jdbcProxy(value, Statement.class);
            return value;
        });
    }
}
