package run.halo.ai.suite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import run.halo.ai.suite.config.AIProperties;
import run.halo.ai.suite.extension.IntentRoute.PipelineStep;
import run.halo.ai.suite.llm.LlmClient;
import run.halo.ai.suite.service.IntentRouteService.SaveIntentRequest;
import run.halo.app.extension.ReactiveExtensionClient;

class IntentRouteGenerationServiceTest {

    private final IntentRouteService routeService = mock(IntentRouteService.class);
    private final IntentRouteGenerationService service = new IntentRouteGenerationService(
        mock(AIProperties.class), mock(LlmClient.class), routeService,
        mock(ReactiveExtensionClient.class));

    @Test
    void inspectDraftWarnsForBroadTriggerAndMultipleLlmSteps() {
        SaveIntentRequest draft = new SaveIntentRequest(
            "", "文章推荐", "", false, 50, List.of("文章"), false, "",
            List.of(step("TOPIC_MATCH"), step("LLM_TITLE_FILTER"), step("TIME_SORT")),
            "只使用真实文章");

        var issues = service.inspectDraft(draft);

        assertThat(issues).extracting(IntentRouteGenerationService.ValidationIssue::message)
            .anyMatch(message -> message.contains("较宽"))
            .anyMatch(message -> message.contains("多个 LLM"));
    }

    @Test
    void inspectDraftReportsUnreachableRoute() {
        SaveIntentRequest draft = new SaveIntentRequest(
            "", "不可达路由", "", false, 0, List.of(), false, "",
            List.of(step("VISIT_SORT")), "只使用真实文章");

        var issues = service.inspectDraft(draft);

        assertThat(issues).extracting(IntentRouteGenerationService.ValidationIssue::level)
            .contains("error");
    }

    @Test
    void inspectDraftRejectsUnknownProcessorParameter() {
        PipelineStep category = step("CATEGORY_MATCH");
        category.getParams().put("category", "旅行日记");
        SaveIntentRequest draft = new SaveIntentRequest(
            "", "旅行文章", "", false, 10, List.of("旅行文章"), false, "",
            List.of(category), "只使用真实文章");

        var issues = service.inspectDraft(draft);

        assertThat(issues).extracting(IntentRouteGenerationService.ValidationIssue::level)
            .contains("error");
    }

    private static PipelineStep step(String type) {
        PipelineStep step = new PipelineStep();
        step.setType(type);
        step.setParams(new LinkedHashMap<>());
        return step;
    }
}
