package cn.rainwu.halo.ai.suite.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

class PetAttachmentServiceTest {
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final AttachmentService attachments = mock(AttachmentService.class);
    private final PetStore store = mock(PetStore.class);
    private final PetAttachmentService service = new PetAttachmentService(client, attachments, store);

    private Attachment asset(String name, Instant created, boolean owned) {
        var attachment = new Attachment(); attachment.setMetadata(new Metadata()); attachment.getMetadata().setName(name);
        attachment.getMetadata().setAnnotations(new java.util.LinkedHashMap<>(Map.of(
            PetAttachmentService.OWNER, owned ? PetAttachmentService.PET_ASSET : "someone-else",
            PetAttachmentService.CREATED, created.toString())));
        when(attachments.getPermalink(attachment)).thenReturn(Mono.just(java.net.URI.create("/" + name + ".png")));
        when(client.fetch(Attachment.class, name)).thenReturn(Mono.just(attachment));
        when(client.delete(attachment)).thenReturn(Mono.just(attachment));
        return attachment;
    }

    @Test void ownershipAndGraceProtectUnrelatedAndInFlightUploads() {
        var old = Instant.now().minusSeconds(7200);
        var unrelated = asset("unrelated", old, false);
        var recent = asset("recent", Instant.now(), true);
        var orphan = asset("orphan", old, true);
        when(store.list()).thenReturn(Mono.just(List.of()));
        when(client.listAll(eq(Attachment.class), any(ListOptions.class), any(Sort.class)))
            .thenReturn(Flux.just(unrelated, recent, orphan));
        assertThat(service.collectUnused().block()).isEqualTo(1);
        verify(client).delete(orphan); verify(client, never()).delete(unrelated); verify(client, never()).delete(recent);
    }

    @Test void allDraftPublishedOriginalAndCandidateReferencesAreProtectedAcrossPets() {
        var pet = new PetStore.PetRecord(); pet.getImages().put("idle", "/draft.png");
        pet.setOriginalMasterUrl("/original.png"); pet.setCleanedMasterUrl("/clean.png");
        var published = new PetStore.PublishedVersion(); published.getImages().put("happy", "/published.png"); pet.setPublishedVersion(published);
        var candidate = new PetStore.ExpressionCandidate(); candidate.setImageUrl("/candidate.png"); candidate.setOriginalUrl("/candidate-original.png"); candidate.setMasterUrl("/candidate-master.png");
        pet.getExpressionCandidates().put("happy", candidate);
        var names = List.of("draft", "original", "clean", "published", "candidate", "candidate-original", "candidate-master");
        var assets = names.stream().map(name -> asset(name, Instant.now().minusSeconds(7200), true)).toList();
        when(store.list()).thenReturn(Mono.just(List.of(pet)));
        when(client.listAll(eq(Attachment.class), any(ListOptions.class), any(Sort.class))).thenReturn(Flux.fromIterable(assets));
        assertThat(service.collectUnused().block()).isZero(); verify(client, never()).delete(any(Attachment.class));
    }

    @Test void corruptConfigurationStopsAllDeletionAndFailedDeletionCanRetry() {
        when(store.list()).thenReturn(Mono.error(new IllegalStateException("corrupt")));
        assertThatThrownBy(() -> service.collectUnused().block()).hasMessage("corrupt");
        verify(client, never()).listAll(eq(Attachment.class), any(), any());
        var orphan = asset("orphan", Instant.now().minusSeconds(7200), true);
        when(store.list()).thenReturn(Mono.just(List.of()));
        when(client.listAll(eq(Attachment.class), any(ListOptions.class), any(Sort.class))).thenReturn(Flux.just(orphan));
        when(client.delete(orphan)).thenReturn(Mono.error(new IllegalStateException("offline")), Mono.just(orphan));
        assertThat(service.collectUnused().block()).isZero();
        assertThat(service.collectUnused().block()).isEqualTo(1);
    }

    @Test void unknownPermalinkNeverDeletesAndInvalidOwnershipTimestampIsIgnored() {
        var orphan = asset("unknown", Instant.now().minusSeconds(7200), true);
        when(attachments.getPermalink(orphan)).thenReturn(Mono.empty());
        when(store.list()).thenReturn(Mono.just(List.of()));
        when(client.listAll(eq(Attachment.class), any(ListOptions.class), any(Sort.class))).thenReturn(Flux.just(orphan));
        assertThat(service.collectUnused().block()).isZero(); verify(client, never()).delete(any(Attachment.class));
        orphan.getMetadata().getAnnotations().put(PetAttachmentService.CREATED, "broken");
        assertThat(PetAttachmentService.collectible(orphan, Instant.now())).isFalse();
    }

    @Test void ownershipIsWrittenBeforeCreationEvenWhenPermalinkFails() {
        var captured = new java.util.concurrent.atomic.AtomicReference<Attachment>();
        when(attachments.upload(anyString(), anyString(), isNull(), any(FilePart.class), any()))
            .thenAnswer(call -> {
                var attachment = new Attachment(); attachment.setMetadata(new Metadata());
                call.<Consumer<Attachment>>getArgument(4).accept(attachment); captured.set(attachment);
                when(attachments.getPermalink(attachment)).thenReturn(Mono.error(new IllegalStateException("url failed")));
                return Mono.just(attachment);
            });
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", "unused", List.of());
        assertThatThrownBy(() -> service.upload("local", new byte[]{1}, "pet-a-idle.png")
            .contextWrite(org.springframework.security.core.context.ReactiveSecurityContextHolder.withAuthentication(auth)).block())
            .hasMessageContaining("url failed");
        assertThat(captured.get().getMetadata().getAnnotations()).containsEntry(PetAttachmentService.OWNER, PetAttachmentService.PET_ASSET);
        assertThat(captured.get().getMetadata().getAnnotations()).containsKey(PetAttachmentService.CREATED);
    }
}
