package com.greytest.service;
import java.util.List;
import org.hibernate.event.spi.*;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;

/** Listener test-only, không thay production configuration. */
final class HibernateFlushProfiler {
    static FlushEventListener flush(List<FlushEventListener> originals) {
        return event->CriticalPathProfiler.unchecked("hibernate.flush",()->{originals.forEach(l->l.onFlush(event));return null;});
    }
    static AutoFlushEventListener auto(List<AutoFlushEventListener> originals) {
        return new AutoFlushEventListener() {
            public void onAutoFlush(AutoFlushEvent event) {CriticalPathProfiler.unchecked("hibernate.autoFlush",()->{originals.forEach(l->l.onAutoFlush(event));return null;});}
            public void onAutoPreFlush(EventSource source) {CriticalPathProfiler.unchecked("hibernate.autoPreFlush",()->{originals.forEach(l->l.onAutoPreFlush(source));return null;});}
        };
    }
    static void install(EntityManagerFactory factory) {
        var registry=factory.unwrap(SessionFactoryImplementor.class).getServiceRegistry().getService(EventListenerRegistry.class);
        var flushers=new java.util.ArrayList<FlushEventListener>();registry.getEventListenerGroup(EventType.FLUSH).listeners().forEach(flushers::add);
        var automatic=new java.util.ArrayList<AutoFlushEventListener>();registry.getEventListenerGroup(EventType.AUTO_FLUSH).listeners().forEach(automatic::add);
        registry.setListeners(EventType.FLUSH,flush(List.copyOf(flushers)));
        registry.setListeners(EventType.AUTO_FLUSH,auto(List.copyOf(automatic)));
    }
}
