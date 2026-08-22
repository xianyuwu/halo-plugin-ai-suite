package cn.rainwu.halo.ai.suite.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rainwu.halo.ai.suite.rag.ReindexService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler.Request;

class PostIndexReconcilerTest {

    @Test
    void shouldExplicitlyDisableSyncAllOnStart() {
        ReindexService service = mock(ReindexService.class);
        PostIndexReconciler reconciler = new PostIndexReconciler(service);
        ControllerBuilder builder = mock(ControllerBuilder.class);
        Controller controller = mock(Controller.class);
        when(builder.extension(any(Post.class))).thenReturn(builder);
        when(builder.syncAllOnStart(false)).thenReturn(builder);
        when(builder.build()).thenReturn(controller);

        assertSame(controller, reconciler.setupWith(builder));

        verify(builder).syncAllOnStart(false);
    }

    @Test
    void shouldFinishWithoutRetryWhenFingerprintIsUnchanged() {
        ReindexService service = mock(ReindexService.class);
        when(service.reindexPostIfChanged("post-a"))
            .thenReturn(Mono.just(new ReindexService.PostReindexResult(0, true)));
        PostIndexReconciler reconciler = new PostIndexReconciler(service);

        var result = reconciler.reconcile(new Request("post-a"));

        assertFalse(result.reEnqueue());
        verify(service).reindexPostIfChanged("post-a");
    }

    @Test
    void shouldStopAutomaticRetriesAfterThreeConsecutiveFailures() {
        ReindexService service = mock(ReindexService.class);
        when(service.reindexPostIfChanged("post-a"))
            .thenReturn(Mono.error(new IllegalStateException("model unavailable")));
        PostIndexReconciler reconciler = new PostIndexReconciler(service);

        assertTrue(reconciler.reconcile(new Request("post-a")).reEnqueue());
        assertTrue(reconciler.reconcile(new Request("post-a")).reEnqueue());
        assertFalse(reconciler.reconcile(new Request("post-a")).reEnqueue());
    }
}
