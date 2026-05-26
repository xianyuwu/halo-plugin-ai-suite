package run.halo.ai.assistant;

import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

@Component
public class AIAssistantPlugin extends BasePlugin {

    public AIAssistantPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        System.out.println("[AI Assistant] 插件启动");
    }

    @Override
    public void stop() {
        System.out.println("[AI Assistant] 插件停止");
    }
}
