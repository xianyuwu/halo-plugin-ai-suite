package cn.rainwu.halo.ai.suite.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import cn.rainwu.halo.ai.suite.config.AIProperties;
import cn.rainwu.halo.ai.suite.llm.LlmClient;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.image.GenerateImageResult;
import run.halo.aifoundation.media.GeneratedFile;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.infra.ExternalUrlSupplier;
import org.springframework.context.ApplicationContext;

class PetCandidateTest {
    private final PetStore store = mock(PetStore.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final AIProperties props = mock(AIProperties.class);
    private final PetStore.PetRecord record = new PetStore.PetRecord();
    private final PetGeneratorService service = spy(new PetGeneratorService(llm, props, store,
        mock(ReactiveExtensionClient.class), mock(ExternalUrlSupplier.class), mock(ApplicationContext.class), mock(PetAttachmentService.class)));

    PetCandidateTest() throws Exception {
        record.setId("pet"); record.setStyle("soft-3d"); record.setBackgroundStatus("approved");
        record.getImages().put("idle", "master"); record.getImages().put("happy", "old-happy");
        when(store.get("pet")).thenAnswer(call -> Mono.just(record));
        when(store.modify(eq("pet"), any())).thenAnswer(call -> Mono.fromSupplier(() -> {
            call.<Consumer<PetStore.PetRecord>>getArgument(1).accept(record); return record;
        }));
        var config = new AIProperties.ModelConfig(); config.setAiFoundationImageModelName("model");
        when(props.getModelConfig()).thenReturn(Mono.just(config));
        doReturn(Mono.just(image(0xFF123456))).when(service).downloadBytes(anyString());
        doAnswer(call -> Mono.just("uploaded/" + call.getArgument(1))).when(service).uploadImage(any(), anyString());
    }

    @Test void confirmedExpressionEditingKeepsPublishedImageUntilApproval() {
        record.publish();
        service.beginExpressionEdit("pet", "happy", "old-happy").block();
        assertThat(record.getImages().get("happy")).isEqualTo("old-happy");
        assertThat(record.getPublishedVersion().getImages().get("happy")).isEqualTo("old-happy");
        assertThat(record.getExpressionCandidates().get("happy").getOriginalUrl()).isEqualTo("old-happy");
        service.applyEdges("pet", "happy", "old-happy", 0, 0, 20).block();
        String draft = record.getExpressionCandidates().get("happy").getImageUrl();
        assertThat(draft).startsWith("uploaded/");
        assertThat(record.getPublishedVersion().getImages().get("happy")).isEqualTo("old-happy");
        service.approveCandidate("pet", "happy", draft).block();
        assertThat(record.getPublishedVersion().getImages().get("happy")).isEqualTo(draft);
        assertThat(record.getImages().get("idle")).isEqualTo("master");
        verifyNoInteractions(llm);
    }

    @Test void cancellingEditedCopyPreservesCurrentAndPublishedVersions() {
        record.publish();
        service.beginExpressionEdit("pet", "happy", "old-happy").block();
        service.applyEdges("pet", "happy", "old-happy", 0, 0, 20).block();
        var draft = record.getExpressionCandidates().get("happy");
        service.cancelExpressionEdit("pet", "happy", draft.getImageUrl()).block();
        assertThat(record.getExpressionCandidates()).isEmpty();
        assertThat(record.getImages().get("happy")).isEqualTo("old-happy");
        assertThat(record.getPublishedVersion().getImages().get("happy")).isEqualTo("old-happy");
        verifyNoInteractions(llm);
    }

    @Test void expressionEditRejectsStaleSourceAndExistingDraft() {
        assertThatThrownBy(() -> service.beginExpressionEdit("pet", "happy", "stale").block()).hasMessageContaining("已变化");
        assertThat(record.getExpressionCandidates()).isEmpty();
        service.beginExpressionEdit("pet", "happy", "old-happy").block();
        assertThatThrownBy(() -> service.beginExpressionEdit("pet", "happy", "old-happy").block()).hasMessageContaining("已变化");
        verify(service, never()).uploadImage(any(), anyString());
    }

    @Test void edgePreviewDoesNotUploadOrMutateAndFailedApplyKeepsImages() {
        service.beginExpressionEdit("pet", "happy", "old-happy").block();
        assertThat(service.previewEdges("pet", "happy", "old-happy", 0, 0, 30).block()).isNotEmpty();
        assertThat(record.getExpressionCandidates().get("happy").getImageUrl()).isEqualTo("old-happy");
        verify(service, never()).uploadImage(any(), anyString());
        doReturn(Mono.error(new IllegalStateException("upload failed"))).when(service).uploadImage(any(), anyString());
        assertThatThrownBy(() -> service.applyEdges("pet", "happy", "old-happy", 0, 0, 30).block()).hasMessageContaining("upload failed");
        assertThat(record.getImages().get("happy")).isEqualTo("old-happy");
        assertThat(record.getExpressionCandidates().get("happy").getImageUrl()).isEqualTo("old-happy");
    }

    @Test void edgeRefinementPreservesOpaqueInteriorAndRejectsUnsafeParameters() throws Exception {
        var source = new BufferedImage(7, 7, BufferedImage.TYPE_INT_ARGB);
        for (int y=1; y<6; y++) for(int x=1; x<6; x++) source.setRGB(x,y,0xff123456);
        source.setRGB(1,3,0x80c0c0c0);
        var bytes = new ByteArrayOutputStream(); ImageIO.write(source,"png",bytes);
        var refined = ImageIO.read(new java.io.ByteArrayInputStream(PetGeneratorService.refineEdges(bytes.toByteArray(),0,100)));
        assertThat(refined.getRGB(3,3)).isEqualTo(0xff123456);
        assertThat(refined.getRGB(1,3) >>> 24).isEqualTo(128);
        assertThat(refined.getRGB(1,3) & 255).isLessThan(192);
        var shrunk = ImageIO.read(new java.io.ByteArrayInputStream(PetGeneratorService.refineEdges(bytes.toByteArray(),1,0)));
        assertThat(shrunk.getRGB(1,1) >>> 24).isZero();
        assertThat(shrunk.getRGB(3,3)).isEqualTo(0xff123456);
        assertThatThrownBy(() -> PetGeneratorService.refineEdges(bytes.toByteArray(),3,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PetGeneratorService.refineEdges(bytes.toByteArray(),0,101)).isInstanceOf(IllegalArgumentException.class);
    }

    private GenerateImageResult result() throws Exception {
        return GenerateImageResult.builder().images(List.of(GeneratedFile.base64(
            java.util.Base64.getEncoder().encodeToString(image(0xFF654321)), "image/png"))).build();
    }

    @Test void masterProgressCompletesOnlyAfterRecordIsSaved() throws Exception {
        var events = new java.util.ArrayList<PetGenerationProgress>();
        when(llm.generateImage(anyString(), anyString(), anyList(), anyString(), anyString())).thenReturn(Mono.just(result()));
        when(store.add(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        service.generatePet(image(0xFF123456), "image/png", "test", "soft-3d", false, "")
            .contextWrite(context -> context.put(PetGenerationProgress.CONTEXT_KEY,
                (Consumer<PetGenerationProgress>) events::add)).block();
        assertThat(events.stream().map(PetGenerationProgress::stage).toList())
            .containsExactly("preparing", "model", "processing", "saving", "frame-complete");
        assertThat(events.getLast().state()).isEqualTo("idle");
        verify(store).add(any());
        verify(llm, times(1)).generateImage(anyString(), anyString(), anyList(), anyString(), anyString());
    }

    @Test void legacyExpressionsCountGeneratedFramesBeforeAtomicSave() throws Exception {
        var events = new java.util.ArrayList<PetGenerationProgress>();
        when(llm.generateImage(anyString(), anyString(), anyList(), anyString(), anyString())).thenReturn(Mono.just(result()));
        service.regenerateExpressions("pet", null, "")
            .contextWrite(context -> context.put(PetGenerationProgress.CONTEXT_KEY,
                (Consumer<PetGenerationProgress>) events::add)).block();
        assertThat(events.stream().filter(e -> e.stage().equals("frame-complete"))
            .map(PetGenerationProgress::state).toList()).containsExactly("blink", "happy", "sad", "thinking");
        int firstSave = java.util.stream.IntStream.range(0, events.size())
            .filter(i -> events.get(i).stage().equals("saving")).findFirst().orElseThrow();
        assertThat(events.subList(0, firstSave).stream().filter(e -> e.stage().equals("frame-complete")).count()).isEqualTo(4);
        assertThat(record.getImages()).containsKeys("blink", "happy", "sad", "thinking");
        verify(llm, times(4)).generateImage(anyString(), anyString(), anyList(), anyString(), anyString());
    }

    @Test void progressReportsOnlyPersistedCandidatesAndDoesNotRetryFailure() throws Exception {
        var events = new java.util.ArrayList<PetGenerationProgress>();
        when(llm.generateImage(anyString(), anyString(), anyList(), anyString(), anyString()))
            .thenReturn(Mono.just(result()), Mono.error(new IllegalStateException("failed")));
        assertThatThrownBy(() -> service.generateCandidateFrames("pet", null, "motion", null, "")
            .contextWrite(context -> context.put(PetGenerationProgress.CONTEXT_KEY,
                (Consumer<PetGenerationProgress>) events::add)).block()).hasMessageContaining("failed");
        assertThat(events.stream().map(PetGenerationProgress::stage).toList())
            .containsExactly("preparing", "model", "processing", "saving", "frame-complete", "preparing", "model");
        assertThat(events.stream().filter(e -> e.stage().equals("frame-complete"))
            .map(PetGenerationProgress::state).toList()).containsExactly("blink");
        verify(llm, times(2)).generateImage(anyString(), anyString(), anyList(), anyString(), anyString());
        assertThat(record.getImages().get("happy")).isEqualTo("old-happy");
    }

    @Test void singleFrameProgressIsScopedToItsSubscription() throws Exception {
        var first = new java.util.ArrayList<PetGenerationProgress>();
        var second = new java.util.ArrayList<PetGenerationProgress>();
        when(llm.generateImage(anyString(), anyString(), anyList(), anyString(), anyString())).thenReturn(Mono.just(result()));
        service.generateCandidateFrames("pet", "happy", "motion", null, "")
            .contextWrite(context -> context.put(PetGenerationProgress.CONTEXT_KEY,
                (Consumer<PetGenerationProgress>) first::add)).block();
        service.generateCandidateFrames("pet", "sad", "motion", null, "")
            .contextWrite(context -> context.put(PetGenerationProgress.CONTEXT_KEY,
                (Consumer<PetGenerationProgress>) second::add)).block();
        assertThat(first.getLast()).isEqualTo(new PetGenerationProgress("frame-complete", "happy"));
        assertThat(second.getLast()).isEqualTo(new PetGenerationProgress("frame-complete", "sad"));
        assertThat(first).noneMatch(e -> e.state().equals("sad"));
    }

    @Test void singleFrameIsStagedAndOnlyExplicitApprovalChangesThatState() throws Exception {
        when(llm.generateImage(anyString(), anyString(), anyList(), anyString(), anyString())).thenReturn(Mono.just(result()));
        service.generateCandidateFrames("pet", "happy", "motion", null, "克制").block();
        assertThat(record.getImages().get("happy")).isEqualTo("old-happy");
        assertThat(record.getExpressionCandidates()).containsOnlyKeys("happy");
        String url = record.getExpressionCandidates().get("happy").getImageUrl();
        service.approveCandidate("pet", "happy", url).block();
        assertThat(record.getImages()).containsEntry("idle", "master").containsEntry("happy", url);
        assertThat(record.getExpressionCandidates()).isEmpty();
        verify(llm, times(1)).generateImage(anyString(), anyString(), anyList(), anyString(), anyString());
    }

    @Test void batchFailureKeepsCompletedCandidateAndAllExistingActiveFrames() throws Exception {
        when(llm.generateImage(anyString(), anyString(), anyList(), anyString(), anyString()))
            .thenReturn(Mono.just(result()), Mono.error(new IllegalStateException("failed")));
        assertThatThrownBy(() -> service.generateCandidateFrames("pet", null, "motion", null, "").block()).hasMessageContaining("failed");
        assertThat(record.getExpressionCandidates()).containsOnlyKeys("blink");
        assertThat(record.getImages()).containsOnlyKeys("idle", "happy").containsEntry("happy", "old-happy");
    }

    @Test void invalidModeStatePixelAndUnconfirmedMasterDoNotCallModel() {
        assertThatThrownBy(() -> service.generateCandidateFrames("pet", "idle", "motion", null, "").block());
        assertThatThrownBy(() -> service.generateCandidateFrames("pet", "happy", "unknown", null, "").block());
        record.setStyle("pixel");
        assertThatThrownBy(() -> service.generateCandidateFrames("pet", "happy", "motion", null, "").block()).hasMessageContaining("精致立体");
        record.setStyle("soft-3d"); record.setBackgroundStatus("pending");
        assertThatThrownBy(() -> service.generateCandidateFrames("pet", "happy", "motion", null, "").block()).hasMessageContaining("母版");
        verifyNoInteractions(llm);
    }

    @Test void staleReviewAndMasterChangesCannotApproveReplacement() throws Exception {
        when(llm.generateImage(anyString(), anyString(), anyList(), anyString(), anyString())).thenReturn(Mono.just(result()));
        service.generateCandidateFrames("pet", "happy", "face", null, "").block();
        var candidate = record.getExpressionCandidates().get("happy");
        assertThatThrownBy(() -> service.approveCandidate("pet", "happy", "stale").block()).hasMessageContaining("变化");
        record.setCleanupRevision(1);
        assertThatThrownBy(() -> service.approveCandidate("pet", "happy", candidate.getImageUrl()).block()).hasMessageContaining("母版");
        assertThat(record.getImages().get("happy")).isEqualTo("old-happy");
    }

    @Test void cleanupAndResetOnlyAffectPendingFrameWithVersionChecks() throws Exception {
        when(llm.generateImage(anyString(), anyString(), anyList(), anyString(), anyString())).thenReturn(Mono.just(result()));
        service.generateCandidateFrames("pet", "happy", "face", null, "").block();
        var candidate = record.getExpressionCandidates().get("happy"); String original = candidate.getOriginalUrl();
        var stroke = new PetStore.EraseStroke(); stroke.setX(.5); stroke.setY(.5); stroke.setRadius(.1);
        service.cleanupCandidate("pet", "happy", original, List.of(stroke)).block();
        assertThat(candidate.getImageUrl()).isNotEqualTo(original);
        assertThatThrownBy(() -> service.approveCandidate("pet", "happy", original).block()).hasMessageContaining("变化");
        service.resetCandidate("pet", "happy", candidate.getImageUrl()).block();
        assertThat(candidate.getImageUrl()).isEqualTo(original);
        assertThat(record.getImages().get("happy")).isEqualTo("old-happy");
    }

    @Test void samePetCannotStartAnotherGenerationUntilCurrentCallCompletes() throws Exception {
        var sink = reactor.core.publisher.Sinks.<GenerateImageResult>one();
        when(llm.generateImage(anyString(), anyString(), anyList(), anyString(), anyString())).thenReturn(sink.asMono());
        var subscription = service.generateCandidateFrames("pet", "happy", "face", null, "").subscribe();
        assertThatThrownBy(() -> service.generateCandidateFrames("pet", "sad", "motion", null, "").block()).hasMessageContaining("正在生成");
        sink.tryEmitValue(result());
        subscription.dispose();
        verify(llm, times(1)).generateImage(anyString(), anyString(), anyList(), anyString(), anyString());
    }

    @Test void changedMasterDuringGenerationCannotStageCandidate() throws Exception {
        GenerateImageResult result = result();
        when(llm.generateImage(anyString(), anyString(), anyList(), anyString(), anyString()))
            .thenAnswer(call -> { record.setCleanupRevision(1); return Mono.just(result); });
        assertThatThrownBy(() -> service.generateCandidateFrames("pet", "happy", "face", null, "").block()).hasMessageContaining("母版");
        assertThat(record.getExpressionCandidates()).isEmpty();
        assertThat(record.getImages().get("happy")).isEqualTo("old-happy");
    }

    @Test void motionPreservesNewSilhouetteWhileBlinkAndFaceKeepMotherAlpha() throws Exception {
        byte[] master = image(0x80123456), other = image(0xFF654321);
        var region = new PetStore.ExpressionRegion();
        var motion = ImageIO.read(new java.io.ByteArrayInputStream(PetGeneratorService.prepareCandidate(master, other, "happy", "motion", region, false)));
        var blink = ImageIO.read(new java.io.ByteArrayInputStream(PetGeneratorService.prepareCandidate(master, other, "blink", "motion", region, false)));
        assertThat(motion.getRGB(0,0) >>> 24).isEqualTo(255);
        assertThat(blink.getRGB(0,0)).isEqualTo(0x80123456);
        assertThat(PetGeneratorService.buildMotionPrompts("").values()).allSatisfy(p -> assertThat(p).contains("鞋底位置不变", "不得出现地面"));
    }

    @Test void pendingMasterKeepsPublishedImagesUntilExactVersionApproval() throws Exception {
        when(llm.generateImage(anyString(), anyString(), anyList(), anyString(), anyString())).thenReturn(Mono.just(result()));
        service.regenerateMaster("pet", image(0xFF123456), "image/png", "new name", "pixel", "").block();
        assertThat(record.getBackgroundStatus()).isEqualTo("pending");
        assertThat(record.publicVersion().getImages()).containsEntry("idle", "master").containsEntry("happy", "old-happy");
        assertThat(record.publicVersion().getStyle()).isEqualTo("soft-3d");
        String draft = record.getImages().get("idle");
        assertThatThrownBy(() -> service.approveBackground("pet", "master", 0).block()).hasMessageContaining("母版已变化");
        assertThatThrownBy(() -> service.approveBackground("pet").block()).hasMessageContaining("页面版本过旧");
        service.approveBackground("pet", draft, record.getCleanupRevision()).block();
        assertThat(record.publicVersion().getImages()).containsOnlyKeys("idle").containsEntry("idle", draft);
        assertThat(record.publicVersion().getStyle()).isEqualTo("pixel");
    }

    @Test void failedMasterDoesNotChangePublicVersionAndReleasesLock() throws Exception {
        when(llm.generateImage(anyString(), anyString(), anyList(), anyString(), anyString()))
            .thenReturn(Mono.error(new IllegalStateException("failed")));
        assertThatThrownBy(() -> service.regenerateMaster("pet", image(0xFF123456), "image/png", "new", "soft-3d", "").block());
        assertThat(record.publicVersion().getImages()).containsEntry("idle", "master").containsEntry("happy", "old-happy");
        service.approveBackground("pet", "master", 0).block();
    }

    @Test void allMutationsRejectWhileModelIsRunningAndCancellationReleasesLock() {
        var sink = reactor.core.publisher.Sinks.<GenerateImageResult>one();
        when(llm.generateImage(anyString(), anyString(), anyList(), anyString(), anyString())).thenReturn(sink.asMono());
        var subscription = service.generateCandidateFrames("pet", "happy", "face", null, "").subscribe();
        assertThatThrownBy(() -> service.approveBackground("pet", "master", 0).block()).hasMessageContaining("正在生成");
        assertThatThrownBy(() -> service.resetBackground("pet", "master", 0).block()).hasMessageContaining("正在生成");
        assertThatThrownBy(() -> service.deletePet("pet").block()).hasMessageContaining("正在生成");
        subscription.dispose();
        service.approveBackground("pet", "master", 0).block();
    }

    @Test void cleanupPreservesPublishedVersionAndRejectsStaleBrushes() {
        var stroke = new PetStore.EraseStroke(); stroke.setX(.5); stroke.setY(.5);
        service.cleanupBackground("pet", List.of(stroke), "master", 0).block();
        assertThat(record.getBackgroundStatus()).isEqualTo("pending");
        assertThat(record.publicVersion().getImages()).containsEntry("idle", "master").containsEntry("happy", "old-happy");
        assertThatThrownBy(() -> service.cleanupBackground("pet", List.of(stroke), "master", 0).block()).hasMessageContaining("母版已变化");
        service.resetBackground("pet", record.getImages().get("idle"), record.getCleanupRevision()).block();
        assertThat(record.publicVersion().getImages().get("happy")).isEqualTo("old-happy");
    }

    @Test void colorMaskCleanupKeepsPublishedFramesAndRejectsStaleMotherOrCandidate() throws Exception {
        var mask = new ColorEraseMask(); mask.setWidth(16); mask.setHeight(16); mask.setRuns(List.of(List.of(0, 2)));
        service.cleanupBackground("pet", List.of(), "master", 0, mask).block();
        assertThat(record.publicVersion().getImages()).containsEntry("idle", "master");
        assertThatThrownBy(() -> service.cleanupBackground("pet", List.of(), "master", 0, mask).block()).hasMessageContaining("母版已变化");
        service.approveBackground("pet", record.getImages().get("idle"), record.getCleanupRevision()).block();
        var approvedFrames = new java.util.LinkedHashMap<>(record.getImages());
        when(llm.generateImage(anyString(), anyString(), anyList(), anyString(), anyString())).thenReturn(Mono.just(result()));
        service.generateCandidateFrames("pet", "happy", "face", null, "").block();
        String url = record.getExpressionCandidates().get("happy").getImageUrl();
        service.cleanupCandidate("pet", "happy", url, List.of(), mask).block();
        assertThat(record.getImages()).isEqualTo(approvedFrames);
        assertThatThrownBy(() -> service.cleanupCandidate("pet", "happy", url, List.of(), mask).block()).hasMessageContaining("变化");
    }

    @Test void renameUpdatesOnlyDraftAndPublishedNamesWithoutPublishingDraftPictures() {
        record.setName("旧名称");
        record.setPublishedVersion(record.snapshot());
        record.setBackgroundStatus("pending");
        record.getImages().put("idle", "unapproved-master");
        var candidate = new PetStore.ExpressionCandidate(); candidate.setImageUrl("unapproved-happy");
        record.getExpressionCandidates().put("happy", candidate);
        service.renamePet("pet", "  新名字  ").block();
        assertThat(record.getName()).isEqualTo("新名字");
        assertThat(record.publicVersion().getName()).isEqualTo("新名字");
        assertThat(record.publicVersion().getImages()).containsEntry("idle", "master").containsEntry("happy", "old-happy");
        assertThat(record.getImages()).containsEntry("idle", "unapproved-master");
        assertThat(record.getExpressionCandidates().get("happy")).isSameAs(candidate);
        assertThat(record.getBackgroundStatus()).isEqualTo("pending");
        assertThat(record.getId()).isEqualTo("pet");
        verifyNoInteractions(llm);
        verify(service, org.mockito.Mockito.never()).uploadImage(any(), anyString());
    }

    @Test void renameValidatesUnicodeAndKeepsNameOnFailure() {
        String valid = "🐱".repeat(20);
        service.renamePet("pet", valid).block();
        for (String invalid : new String[]{null, "   ", "🐱".repeat(21), "name\nline"}) {
            assertThatThrownBy(() -> service.renamePet("pet", invalid).block()).hasMessageContaining("宠物名称");
            assertThat(record.getName()).isEqualTo(valid);
        }
        assertThat(record.getPublishedVersion()).isNull();
        verifyNoInteractions(llm);
    }

    @Test void renameCannotRaceActiveGenerationAndCanRetryAfterCancellation() {
        var sink = reactor.core.publisher.Sinks.<GenerateImageResult>one();
        when(llm.generateImage(anyString(), anyString(), anyList(), anyString(), anyString())).thenReturn(sink.asMono());
        var subscription = service.generateCandidateFrames("pet", "happy", "face", null, "").subscribe();
        assertThatThrownBy(() -> service.renamePet("pet", "new").block()).hasMessageContaining("正在生成");
        subscription.dispose();
        service.renamePet("pet", "new").block();
        assertThat(record.getName()).isEqualTo("new");
    }

    private static byte[] image(int color) throws Exception {
        var image = new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
        for (int y=0;y<16;y++) for (int x=0;x<16;x++) image.setRGB(x,y,color);
        var out = new ByteArrayOutputStream(); ImageIO.write(image,"png",out); return out.toByteArray();
    }
}
