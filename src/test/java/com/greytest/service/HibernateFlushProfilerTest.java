package com.greytest.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.hibernate.event.spi.*;

/** Kiểm chứng instrumentation không bỏ listener/auto-preflush và không đổi exception. */
class HibernateFlushProfilerTest {
    @Test void flushMeasuresOriginalWorkAndCallsEveryListenerOnce() throws Throwable {
        var first=mock(FlushEventListener.class);var second=mock(FlushEventListener.class);var event=mock(FlushEvent.class);
        doAnswer(call->{Thread.sleep(10);return null;}).when(first).onFlush(event);
        var observed=HibernateFlushProfiler.flush(List.of(first,second));
        var run=new CriticalPathProfiler.Run("flush",1,2);
        CriticalPathProfiler.bind(run,()->{observed.onFlush(event);return null;});
        verify(first).onFlush(event);verify(second).onFlush(event);
        assertThat(run.spans()).singleElement().satisfies(s->{assertThat(s.name()).isEqualTo("hibernate.flush");
            assertThat(s.endNanos()-s.startNanos()).isGreaterThanOrEqualTo(5_000_000);});
    }
    @Test void autoFlushKeepsBothCallbacks() throws Throwable {
        var original=mock(AutoFlushEventListener.class);var event=mock(AutoFlushEvent.class);var source=mock(EventSource.class);
        var observed=HibernateFlushProfiler.auto(List.of(original));var run=new CriticalPathProfiler.Run("auto",1,2);
        CriticalPathProfiler.bind(run,()->{observed.onAutoPreFlush(source);observed.onAutoFlush(event);return null;});
        verify(original).onAutoPreFlush(source);verify(original).onAutoFlush(event);assertThat(run.spans()).hasSize(2);
    }
    @Test void originalExceptionIsPreservedAndRecorded() throws Throwable {
        var original=mock(FlushEventListener.class);var event=mock(FlushEvent.class);var error=new org.hibernate.HibernateException("expected");
        doThrow(error).when(original).onFlush(event);var run=new CriticalPathProfiler.Run("error",1,2);
        assertThatThrownBy(()->CriticalPathProfiler.bind(run,()->{HibernateFlushProfiler.flush(List.of(original)).onFlush(event);return null;})).isSameAs(error);
        assertThat(run.spans()).singleElement().satisfies(s->assertThat(s.failed()).isTrue());
    }
}
