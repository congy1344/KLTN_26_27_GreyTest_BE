package com.greytest.service;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;

/** Sampler chỉ dùng trong benchmark: không lấy SQL text, không mượn Hikari connection, không đổi DB settings. */
final class TailLatencyObserver implements AutoCloseable {
    private static volatile TailLatencyObserver current;
    private static volatile HikariDataSource pool;
    private final ConcurrentHashMap<String,CriticalPathProfiler.Run> runs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task,"greytest-tail-observer"); thread.setDaemon(true); return thread;
    });
    private final Connection connection;

    static boolean enabled() { return Boolean.getBoolean("benchmark.tail-observer"); }
    static TailLatencyObserver startIfEnabled() throws SQLException {
        if (!enabled()) return null;
        if (current != null) throw new IllegalStateException("Tail observer already active");
        String url=System.getenv("SPRING_DATASOURCE_URL");
        if (!"jdbc:postgresql://localhost:5433/greytest_incremental_test_20261024".equals(url))
            throw new IllegalArgumentException("Dedicated benchmark database required");
        var properties=new Properties();
        properties.setProperty("user",System.getenv("SPRING_DATASOURCE_USERNAME"));
        properties.setProperty("password",System.getenv("SPRING_DATASOURCE_PASSWORD"));
        properties.setProperty("ApplicationName","greytest-tail-observer");
        properties.setProperty("connectTimeout","3"); properties.setProperty("socketTimeout","3");
        var observer=new TailLatencyObserver(DriverManager.getConnection(url,properties));
        current=observer;
        observer.executor.scheduleWithFixedDelay(observer::sample,0,20,TimeUnit.MILLISECONDS);
        return observer;
    }
    private TailLatencyObserver(Connection connection) { this.connection=connection; }

    static void registerPool(DataSource source) {
        if (enabled() && source instanceof HikariDataSource hikari) pool=hikari;
    }
    static void enter(CriticalPathProfiler.Run run) {
        var observer=current;
        if (observer!=null) { synchronized(run) {run.active=true;} observer.runs.put(run.id,run); }
    }
    static void exit(CriticalPathProfiler.Run run) {
        var observer=current;
        synchronized(run) {run.active=false;}
        if (observer!=null) observer.runs.remove(run.id);
    }
    static void pidUnavailable() {
        var observer=current;
        if(observer!=null) for(var run:observer.runs.values()) synchronized(run) {
            if(run.active) run.observerErrors.add(Map.of("kind","backend-pid-unavailable"));
        }
    }

    private synchronized void sample() {
        if (runs.isEmpty()) return;
        var before=new HashMap<CriticalPathProfiler.Run,Map<Integer,CriticalPathProfiler.ActiveJdbc>>();
        for(var run:runs.values()) before.put(run,new HashMap<>(run.activeJdbc));
        long started=System.nanoTime();
        try (var statement=connection.createStatement()) {
            statement.setQueryTimeout(2);
            try(var rows=statement.executeQuery("""
                    SELECT pid,state,wait_event_type,wait_event,pg_blocking_pids(pid) AS blockers,
                        xact_start::text AS xact_start,query_start::text AS query_start,backend_xid::text AS backend_xid
                    FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()
                    """)) {
                while(rows.next()) {
                    int pid=rows.getInt("pid");
                    for(var entry:before.entrySet()) {
                        var run=entry.getKey(); var call=entry.getValue().get(pid);
                        if(call==null) continue;
                        var blockers=new ArrayList<Integer>();
                        var array=rows.getArray("blockers");
                        try { if(array!=null) for(Object value:(Object[])array.getArray()) blockers.add(((Number)value).intValue()); }
                        finally { if(array!=null) array.free(); }
                        var sample=new LinkedHashMap<String,Object>();
                        sample.put("spanId",call.spanId()); sample.put("backendPid",pid); sample.put("thread",call.thread());
                        sample.put("stage",call.stage()); sample.put("batch",call.batch()); sample.put("targets",call.targets());
                        sample.put("operation",call.operation()); sample.put("purpose",call.purpose());
                        sample.put("startNanos",started-run.started); sample.put("endNanos",System.nanoTime()-run.started);
                        sample.put("state",rows.getString("state")); sample.put("waitType",rows.getString("wait_event_type"));
                        sample.put("waitEvent",rows.getString("wait_event")); sample.put("blockers",blockers);
                        if(Boolean.getBoolean("benchmark.transaction-detail")) {
                            sample.put("xactStart",rows.getString("xact_start"));sample.put("queryStart",rows.getString("query_start"));
                            sample.put("backendXid",rows.getString("backend_xid"));
                        }
                        // Cùng call phải còn active ở cả hai phía query sampler, tránh gán PID tái sử dụng sang batch khác.
                        synchronized(run) {
                            if(run.active && call.equals(run.activeJdbc.get(pid))) run.databaseSamples.add(sample);
                        }
                    }
                }
            }
            if(Boolean.getBoolean("benchmark.transaction-detail")) captureLocks(before,started);
            var observedPool=pool; var bean=observedPool==null?null:observedPool.getHikariPoolMXBean();
            if(bean!=null) for(var run:before.keySet()) synchronized(run) {
                if(run.active) run.poolSamples.add(Map.of("atNanos",System.nanoTime()-run.started,
                        "active",bean.getActiveConnections(),"idle",bean.getIdleConnections(),
                        "total",bean.getTotalConnections(),"awaiting",bean.getThreadsAwaitingConnection()));
            }
        } catch(SQLException failure) {
            for(var run:before.keySet()) synchronized(run) {
                if(run.active) run.observerErrors.add(Map.of("kind",failure.getClass().getSimpleName(),
                        "sqlState",Objects.toString(failure.getSQLState(),"unknown")));
            }
        }
    }
    /** Cùng JDBC call phải còn active sau cả snapshot activity và pg_locks; không xem hai query là atomic. */
    private void captureLocks(Map<CriticalPathProfiler.Run,Map<Integer,CriticalPathProfiler.ActiveJdbc>> before,long started) throws SQLException {
        var locks=new ArrayList<Map<String,Object>>();
        try(var statement=connection.createStatement()) {
            statement.setQueryTimeout(2);
            try(var rows=statement.executeQuery("""
                SELECT pid,locktype,relation::regclass::text AS relation,transactionid::text AS xid,
                    virtualxid,mode,granted,fastpath FROM pg_locks
                WHERE pid IN(SELECT pid FROM pg_stat_activity WHERE datname=current_database())
                    AND pid<>pg_backend_pid()
                """)) {
                while(rows.next()) {
                    var lock=new LinkedHashMap<String,Object>();lock.put("pid",rows.getInt("pid"));lock.put("type",rows.getString("locktype"));
                    lock.put("relation",rows.getString("relation"));lock.put("xid",rows.getString("xid"));lock.put("virtualXid",rows.getString("virtualxid"));
                    lock.put("mode",rows.getString("mode"));lock.put("granted",rows.getBoolean("granted"));lock.put("fastpath",rows.getBoolean("fastpath"));locks.add(lock);
                }
            }
        }
        long ended=System.nanoTime();
        for(var entry:before.entrySet()) {
            var run=entry.getKey();
            for(var sample:run.databaseSamples) {
                if(!Objects.equals(sample.get("startNanos"),started-run.started)) continue;
                int pid=(int)sample.get("backendPid");
                var call=entry.getValue().get(pid);
                @SuppressWarnings("unchecked") var blockers=(List<Integer>)sample.get("blockers");
                if(blockers.isEmpty()) continue;
                synchronized(run) {
                    if(run.active && call.equals(run.activeJdbc.get(pid))) {
                        sample.put("locksEndNanos",ended-run.started);
                        sample.put("locks",locks.stream().filter(l->(int)l.get("pid")==pid || blockers.contains(l.get("pid"))).toList());
                    }
                }
            }
        }
    }
    /** Counter snapshots ngoài root; server-global và có statistics lag, không phải per-run attribution. */
    synchronized String statistics() throws SQLException {
        try(var statement=connection.createStatement()) {
            statement.setQueryTimeout(2);
            try(var rows=statement.executeQuery("""
                SELECT json_build_object('wal',(SELECT row_to_json(w) FROM pg_stat_wal w),
                    'database',(SELECT row_to_json(d) FROM pg_stat_database d WHERE datname=current_database()),
                    'bgwriter',(SELECT row_to_json(b) FROM pg_stat_bgwriter b),
                    'observedAt',clock_timestamp())::text
                """)) {rows.next();return rows.getString(1);}
        }
    }
    @Override public void close() throws SQLException {
        current=null; executor.shutdownNow();
        try { if(!executor.awaitTermination(5,TimeUnit.SECONDS)) throw new IllegalStateException("Sampler did not stop"); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        finally { connection.close(); runs.clear(); pool=null; }
    }
}
