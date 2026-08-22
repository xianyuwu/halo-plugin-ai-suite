package cn.rainwu.halo.ai.suite.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import cn.rainwu.halo.ai.suite.config.AIProperties;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class LuceneIndexFingerprintTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistFingerprintWithTheIndexedDocument() throws Exception {
        LuceneIndexService service = new LuceneIndexService(mock(AIProperties.class));
        ReflectionTestUtils.setField(service, "workDir", tempDir.toString());
        TextChunk chunk = new TextChunk("post-a_0", "post-a", "标题", "正文", 0, List.of());
        var indexed = List.of(new LuceneIndexService.IndexedChunk(chunk, new float[] {1f, 0f}));
        try {
            service.replacePost(indexed, "embedding-model", 2, "fingerprint-a");
            assertEquals("fingerprint-a", service.getPostFingerprint("post-a"));

            service.replacePost(indexed, "embedding-model", 2, "fingerprint-b");
            assertEquals("fingerprint-b", service.getPostFingerprint("post-a"));

            service.deleteByPostId("post-a");
            assertNull(service.getPostFingerprint("post-a"));
        } finally {
            service.close();
        }
    }
}
