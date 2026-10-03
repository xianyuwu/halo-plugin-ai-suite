package cn.rainwu.halo.ai.suite.endpoint;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rainwu.halo.ai.suite.service.PetGeneratorService;
import cn.rainwu.halo.ai.suite.service.PetStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

class ConsolePetPresetsTest {
    @Test
    void pollingReceivesProgressFromDetachedGenerationContext() throws Exception {
        var generator = mock(PetGeneratorService.class);
        var hold = reactor.core.publisher.Sinks.<PetStore.PetRecord>one();
        var observed = new java.util.concurrent.CountDownLatch(1);
        when(generator.generateCandidateFrames(eq("pet"), eq("happy"), eq("motion"), any(), any()))
            .thenReturn(cn.rainwu.halo.ai.suite.service.PetGenerationProgress.track(
                Mono.defer(() -> { observed.countDown(); return hold.asMono(); }), "model", "happy"));
        var endpoint = new ConsolePetEndpoint(generator, mock(PetStore.class), mock(ReactiveExtensionClient.class));
        var web = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();
        String response = web.post().uri("/pets/pet/expressions/generate")
            .header("Content-Type", "application/json")
            .bodyValue("{\"state\":\"happy\",\"mode\":\"motion\"}").exchange()
            .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("jobId").asText();
        org.assertj.core.api.Assertions.assertThat(observed.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        web.get().uri("/pets/jobs/" + id).exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.job.status").isEqualTo("pending")
            .jsonPath("$.job.progress.stage").isEqualTo("model")
            .jsonPath("$.job.progress.currentState").isEqualTo("happy")
            .jsonPath("$.job.progress.total").isEqualTo(1)
            .jsonPath("$.job.progress.completed").isEqualTo(0);
        hold.tryEmitError(new IllegalStateException("original failure"));
    }

    @Test
    void jobProgressPreservesCountsAndFreezesAfterFailure() throws Exception {
        var job = new ConsolePetEndpoint.PetJob("job");
        job.progress(new cn.rainwu.halo.ai.suite.service.PetGenerationProgress("model", "happy"));
        job.progress(new cn.rainwu.halo.ai.suite.service.PetGenerationProgress("processing", ""));
        var progress = (java.util.Map<?, ?>) job.toMap().get("progress");
        org.assertj.core.api.Assertions.assertThat(progress.get("currentState")).isEqualTo("happy");
        org.assertj.core.api.Assertions.assertThat(progress.get("completed")).isEqualTo(0);
        job.progress(new cn.rainwu.halo.ai.suite.service.PetGenerationProgress("frame-complete", "happy"));
        job.progress(new cn.rainwu.halo.ai.suite.service.PetGenerationProgress("frame-complete", "happy"));
        job.fail("original error");
        var failed = job.toMap();
        job.progress(new cn.rainwu.halo.ai.suite.service.PetGenerationProgress("model", "sad"));
        org.assertj.core.api.Assertions.assertThat(job.toMap()).isEqualTo(failed);
        org.assertj.core.api.Assertions.assertThat(((java.util.Map<?, ?>) failed.get("progress")).get("completed")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(failed.get("error")).isEqualTo("original error");
    }

    @Test
    void candidateReviewAndSingleGenerationUseStateAndExpectedVersion() {
        var generator = mock(PetGeneratorService.class);
        var record = new PetStore.PetRecord(); record.setId("pet-1");
        when(generator.approveCandidate("pet-1", "happy", "candidate-v1")).thenReturn(Mono.just(record));
        when(generator.generateCandidateFrames(eq("pet-1"), eq("thinking"), eq("motion"), any(), eq("克制")))
            .thenReturn(Mono.just(record));
        var endpoint = new ConsolePetEndpoint(generator, mock(PetStore.class), mock(ReactiveExtensionClient.class));
        var web = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();
        web.post().uri("/pets/pet-1/expressions/happy/approve").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(java.util.Map.of("candidateUrl", "candidate-v1")).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.success").isEqualTo(true);
        web.post().uri("/pets/pet-1/expressions/generate").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(java.util.Map.of("state", "thinking", "mode", "motion", "customPrompt", "克制"))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.jobId").exists();
        verify(generator, timeout(1000)).generateCandidateFrames(eq("pet-1"), eq("thinking"), eq("motion"), any(), eq("克制"));
        verify(generator).approveCandidate("pet-1", "happy", "candidate-v1");
    }

    @Test
    void modelStatusExposesConfigurationGuidance() {
        var generator = mock(PetGeneratorService.class);
        when(generator.imageModelStatus()).thenReturn(Mono.just(
            new cn.rainwu.halo.ai.suite.llm.AiFoundationClient.ImageModelStatus(false, "missing", "请配置生图模型")));
        var endpoint = new ConsolePetEndpoint(generator, mock(PetStore.class), mock(ReactiveExtensionClient.class));
        WebTestClient.bindToRouterFunction(endpoint.endpoint()).build()
            .get().uri("/pets/model-status").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.available").isEqualTo(false)
            .jsonPath("$.reason").isEqualTo("missing")
            .jsonPath("$.message").isEqualTo("请配置生图模型");
    }

    @Test
    void presetsExposeOnlyThreeNewSkins() {
        var endpoint = new ConsolePetEndpoint(mock(PetGeneratorService.class),
            mock(PetStore.class), mock(ReactiveExtensionClient.class));
        WebTestClient.bindToRouterFunction(endpoint.endpoint()).build()
            .get().uri("/pets/presets").exchange()
            .expectStatus().isOk().expectBody()
            .jsonPath("$.presets.length()").isEqualTo(3)
            .jsonPath("$.presets[0].name").isEqualTo("mint-robot")
            .jsonPath("$.presets[0].preview")
                .isEqualTo("/plugins/ai-suite/assets/res/pets/mint-robot/idle.png")
            .jsonPath("$.presets[0].manifest.images.idle")
                .isEqualTo("/plugins/ai-suite/assets/res/pets/mint-robot/idle.png")
            .jsonPath("$.presets[0].manifest.images.thinking")
                .isEqualTo("/plugins/ai-suite/assets/res/pets/mint-robot/thinking.png")
            .jsonPath("$.presets[1].name").isEqualTo("cream-cat")
            .jsonPath("$.presets[1].preview")
                .isEqualTo("/plugins/ai-suite/assets/res/pets/cream-cat/idle.png")
            .jsonPath("$.presets[1].manifest.images.thinking")
                .isEqualTo("/plugins/ai-suite/assets/res/pets/cream-cat/thinking.png")
            .jsonPath("$.presets[2].name").isEqualTo("star-sprite")
            .jsonPath("$.presets[2].preview")
                .isEqualTo("/plugins/ai-suite/assets/res/pets/star-sprite/idle.png")
            .jsonPath("$.presets[2].manifest.images.thinking")
                .isEqualTo("/plugins/ai-suite/assets/res/pets/star-sprite/thinking.png");
    }

    @Test
    void regenerateAcceptsExpressionRegion() {
        var generator = mock(PetGeneratorService.class);
        var store = mock(PetStore.class);
        var record = new PetStore.PetRecord();
        record.setId("pet-1");
        when(generator.regenerateExpressions(eq("pet-1"),
            org.mockito.ArgumentMatchers.any(PetStore.ExpressionRegion.class),
            eq("表情克制")))
            .thenReturn(Mono.just(record));
        var endpoint = new ConsolePetEndpoint(generator, store,
            mock(ReactiveExtensionClient.class));

        WebTestClient.bindToRouterFunction(endpoint.endpoint()).build()
            .post().uri("/pets/pet-1/regenerate")
            .header("Content-Type", "application/json")
            .bodyValue("""
                {"centerX":0.42,"centerY":0.31,"width":0.5,"height":0.4,"feather":0.1,
                 "customPrompt":"表情克制"}
                """)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.success").isEqualTo(true)
            .jsonPath("$.job.status").isEqualTo("pending");

        verify(generator, timeout(1000)).regenerateExpressions(eq("pet-1"), argThat(region ->
                region.getCenterX() == 0.42
                    && region.getCenterY() == 0.31
                    && region.getWidth() == 0.5
                    && region.getHeight() == 0.4
                    && region.getFeather() == 0.1),
            eq("表情克制"));
    }

    @Test
    void promptPreviewReturnsServerComposedPrompts() {
        var generator = mock(PetGeneratorService.class);
        when(generator.previewPrompts("soft-3d", "不要阴影", "不要露牙", "face"))
            .thenReturn(java.util.Map.of(
                "master", "master-final",
                "expressions", java.util.Map.of("happy", "happy-final")));
        var endpoint = new ConsolePetEndpoint(generator, mock(PetStore.class),
            mock(ReactiveExtensionClient.class));

        WebTestClient.bindToRouterFunction(endpoint.endpoint()).build()
            .post().uri("/pets/prompt-preview")
            .header("Content-Type", "application/json")
            .bodyValue("""
                {"style":"soft-3d","customPrompt":"不要阴影","expressionPrompt":"不要露牙"}
                """)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.success").isEqualTo(true)
            .jsonPath("$.prompts.master").isEqualTo("master-final")
            .jsonPath("$.prompts.expressions.happy").isEqualTo("happy-final");
    }

    @Test
    void regenerateMasterAcceptsPhotoAndCustomPrompt() {
        var generator = mock(PetGeneratorService.class);
        var record = new PetStore.PetRecord();
        record.setId("pet-1");
        when(generator.regenerateMaster(eq("pet-1"), any(byte[].class), eq("image/png"),
            eq("机器人"), eq("soft-3d"), eq("不要脚下阴影")))
            .thenReturn(Mono.just(record));
        var endpoint = new ConsolePetEndpoint(generator, mock(PetStore.class),
            mock(ReactiveExtensionClient.class));
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(new byte[] {1, 2, 3}) {
            @Override
            public String getFilename() {
                return "robot.png";
            }
        }).contentType(MediaType.IMAGE_PNG);
        body.part("name", "机器人");
        body.part("style", "soft-3d");
        body.part("customPrompt", "不要脚下阴影");

        WebTestClient.bindToRouterFunction(endpoint.endpoint()).build()
            .post().uri("/pets/pet-1/regenerate-master")
            .body(BodyInserters.fromMultipartData(body.build()))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.success").isEqualTo(true)
            .jsonPath("$.job.status").isEqualTo("pending");

        verify(generator, timeout(1000)).regenerateMaster(eq("pet-1"), any(byte[].class),
            eq("image/png"), eq("机器人"), eq("soft-3d"), eq("不要脚下阴影"));
    }

    @Test
    void cleanupBackgroundAcceptsBrushStrokes() {
        var generator = mock(PetGeneratorService.class);
        var record = new PetStore.PetRecord();
        record.setId("pet-1");
        when(generator.cleanupBackground(eq("pet-1"), any(), eq("master"), eq(0), org.mockito.ArgumentMatchers.isNull()))
            .thenReturn(Mono.just(record));
        var endpoint = new ConsolePetEndpoint(generator, mock(PetStore.class),
            mock(ReactiveExtensionClient.class));

        WebTestClient.bindToRouterFunction(endpoint.endpoint()).build()
            .post().uri("/pets/pet-1/background/cleanup")
            .header("Content-Type", "application/json")
            .bodyValue("""
                {"masterUrl":"master","masterRevision":0,"strokes":[{"x":0.44,"y":0.82,"radius":0.06}]}
                """)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.success").isEqualTo(true)
            .jsonPath("$.job.status").isEqualTo("pending");

        verify(generator, timeout(1000)).cleanupBackground(eq("pet-1"), argThat(strokes ->
            strokes.size() == 1
                && strokes.getFirst().getX() == 0.44
                && strokes.getFirst().getY() == 0.82
                && strokes.getFirst().getRadius() == 0.06), eq("master"), eq(0), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void colorMaskOnlyRequestsReachBothCleanupPathsWithVersionGuards() {
        var generator = mock(PetGeneratorService.class);
        var record = new PetStore.PetRecord(); record.setId("pet-1");
        when(generator.cleanupBackground(eq("pet-1"), any(), eq("master"), eq(3), any())).thenReturn(Mono.just(record));
        when(generator.cleanupCandidate(eq("pet-1"), eq("happy"), eq("candidate"), any(), any())).thenReturn(Mono.just(record));
        var web = WebTestClient.bindToRouterFunction(new ConsolePetEndpoint(generator, mock(PetStore.class), mock(ReactiveExtensionClient.class)).endpoint()).build();
        var mask = java.util.Map.of("width", 4, "height", 4, "runs", List.of(List.of(0, 2)));
        web.post().uri("/pets/pet-1/background/cleanup").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(java.util.Map.of("masterUrl", "master", "masterRevision", 3, "mask", mask)).exchange()
            .expectStatus().isOk().expectBody().jsonPath("$.success").isEqualTo(true);
        verify(generator, timeout(1000)).cleanupBackground(eq("pet-1"), argThat(List::isEmpty), eq("master"), eq(3),
            argThat(m -> m.getWidth() == 4 && m.getRuns().getFirst().equals(List.of(0, 2))));
        web.post().uri("/pets/pet-1/expressions/happy/cleanup").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(java.util.Map.of("candidateUrl", "candidate", "mask", mask)).exchange().expectStatus().isOk();
        verify(generator).cleanupCandidate(eq("pet-1"), eq("happy"), argThat("candidate"::equals), argThat(List::isEmpty), any());
    }

    @Test
    void renameRouteReturnsRecordAndPropagatesValidationAndMissingPetErrors() {
        var generator = mock(PetGeneratorService.class);
        var record = new PetStore.PetRecord(); record.setId("pet-1"); record.setName("新名称");
        when(generator.renamePet("pet-1", "新名称")).thenReturn(Mono.just(record));
        when(generator.renamePet("pet-1", "")).thenReturn(Mono.error(new IllegalArgumentException("宠物名称不能为空")));
        when(generator.renamePet("missing", "新名称")).thenReturn(Mono.error(new IllegalArgumentException("宠物不存在")));
        var web = WebTestClient.bindToRouterFunction(new ConsolePetEndpoint(generator, mock(PetStore.class), mock(ReactiveExtensionClient.class)).endpoint()).build();
        web.post().uri("/pets/pet-1/rename").contentType(MediaType.APPLICATION_JSON).bodyValue(java.util.Map.of("name", "新名称"))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.pet.name").isEqualTo("新名称");
        web.post().uri("/pets/pet-1/rename").contentType(MediaType.APPLICATION_JSON).bodyValue(java.util.Map.of("name", ""))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.success").isEqualTo(false);
        web.post().uri("/pets/missing/rename").contentType(MediaType.APPLICATION_JSON).bodyValue(java.util.Map.of("name", "新名称"))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.success").isEqualTo(false);
        web.post().uri("/pets/pet-1/rename").contentType(MediaType.APPLICATION_JSON).exchange().expectStatus().isOk().expectBody().jsonPath("$.success").isEqualTo(false);
    }

    @Test
    void editAndEdgePreviewRoutesKeepPreviewSeparateFromApply() {
        var generator = mock(PetGeneratorService.class);
        var record = new PetStore.PetRecord(); record.setId("pet-1");
        when(generator.beginExpressionEdit("pet-1", "happy", "current")).thenReturn(Mono.just(record));
        when(generator.cancelExpressionEdit("pet-1", "happy", "current")).thenReturn(Mono.just(record));
        when(generator.previewEdges("pet-1", "happy", "current", 0, 1, 20)).thenReturn(Mono.just(new byte[]{1,2,3}));
        when(generator.applyEdges("pet-1", "happy", "current", 0, 1, 20)).thenReturn(Mono.just(record));
        var web = WebTestClient.bindToRouterFunction(new ConsolePetEndpoint(generator, mock(PetStore.class), mock(ReactiveExtensionClient.class)).endpoint()).build();
        web.post().uri("/pets/pet-1/expressions/happy/edit").contentType(MediaType.APPLICATION_JSON).bodyValue(java.util.Map.of("candidateUrl","current"))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.pet.id").isEqualTo("pet-1");
        var parameters = java.util.Map.of("state","happy","url","current","revision",0,"shrink",1,"dewhite",20);
        web.post().uri("/pets/pet-1/background/edge-preview").contentType(MediaType.APPLICATION_JSON).bodyValue(parameters)
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.image").isEqualTo("data:image/png;base64,AQID");
        verify(generator, never()).applyEdges(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());
        web.post().uri("/pets/pet-1/background/edge-apply").contentType(MediaType.APPLICATION_JSON).bodyValue(parameters)
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.pet.id").isEqualTo("pet-1");
        web.post().uri("/pets/pet-1/expressions/happy/cancel-edit").contentType(MediaType.APPLICATION_JSON).bodyValue(java.util.Map.of("candidateUrl","current"))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.pet.id").isEqualTo("pet-1");
    }

    @Test
    void approveBackgroundReturnsUpdatedPet() {
        var generator = mock(PetGeneratorService.class);
        var record = new PetStore.PetRecord();
        record.setId("pet-1");
        record.setBackgroundStatus(PetGeneratorService.BACKGROUND_APPROVED);
        when(generator.approveBackground("pet-1", "master", 0)).thenReturn(Mono.just(record));
        var endpoint = new ConsolePetEndpoint(generator, mock(PetStore.class),
            mock(ReactiveExtensionClient.class));

        WebTestClient.bindToRouterFunction(endpoint.endpoint()).build()
            .post().uri("/pets/pet-1/background/approve")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue(java.util.Map.of("masterUrl", "master", "masterRevision", 0))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.success").isEqualTo(true)
            .jsonPath("$.pet.backgroundStatus").isEqualTo("approved");

        verify(generator).approveBackground("pet-1", "master", 0);
    }

    @Test
    void resetBackgroundReturnsPendingPet() {
        var generator = mock(PetGeneratorService.class);
        var record = new PetStore.PetRecord();
        record.setId("pet-1");
        record.setBackgroundStatus(PetGeneratorService.BACKGROUND_PENDING);
        when(generator.resetBackground("pet-1", "master", 0)).thenReturn(Mono.just(record));
        var endpoint = new ConsolePetEndpoint(generator, mock(PetStore.class),
            mock(ReactiveExtensionClient.class));

        WebTestClient.bindToRouterFunction(endpoint.endpoint()).build()
            .post().uri("/pets/pet-1/background/reset")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue(java.util.Map.of("masterUrl", "master", "masterRevision", 0))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.success").isEqualTo(true)
            .jsonPath("$.pet.backgroundStatus").isEqualTo("pending");

        verify(generator).resetBackground("pet-1", "master", 0);
    }
}
