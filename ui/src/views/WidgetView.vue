<template>
  <div class="widget-page">
    <div class="ai-content">
      <!-- 左侧：固定标题 + 可滚动卡片 -->
      <div class="chat-config">
        <div class="chat-config-scroll">
        <SectionCard title="" :icon-component="RiPaletteLine" headerTitle="浮窗样式" headerDesc="自定义浮窗的位置、主题色、尺寸与深浅色模式">
          <div class="ai-card-body">
            <div class="ai-form-grid-2">
              <div class="ai-form-field">
                <label class="ai-field-label">浮窗位置</label>
                <select class="ai-input ai-select" v-model="form.widgetPosition">
                  <option value="right-bottom">右下角</option>
                  <option value="left-bottom">左下角</option>
                </select>
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">深浅色模式</label>
                <select class="ai-input ai-select" v-model="form.widgetTheme">
                  <option value="auto">自动适配博客</option>
                  <option value="system">跟随系统</option>
                  <option value="light">强制浅色</option>
                  <option value="dark">强制深色</option>
                </select>
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">窗口宽度 (px)</label>
                <input class="ai-input" v-model.number="form.widgetWidth" type="number" min="300" max="600" />
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">窗口高度 (px)</label>
                <input class="ai-input" v-model.number="form.widgetHeight" type="number" min="400" max="800" />
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">按钮垂直位置</label>
                <select class="ai-input ai-select" v-model="form.widgetTriggerAlign">
                  <option value="auto">自动避让页面悬浮按钮</option>
                  <option value="manual">手动指定距底像素</option>
                </select>
                <div class="ai-helper-text">推荐移动端使用；加载时预留稳定位置，滚动过程中不会跳动</div>
              </div>
              <div class="ai-form-field" v-if="form.widgetTriggerAlign === 'manual'">
                <label class="ai-field-label">按钮距底部 (px)</label>
                <input class="ai-input" v-model.number="form.widgetTriggerOffsetY" type="number" min="16" max="240" />
                <div class="ai-helper-text">建议 80-120</div>
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">按钮水平边距 (px)</label>
                <input class="ai-input" v-model.number="form.widgetTriggerOffsetX" type="number" min="0" max="120" />
                <div class="ai-helper-text">距左/右边缘的距离，建议 16-32</div>
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">按钮尺寸 (px)</label>
                <input class="ai-input" v-model.number="form.widgetTriggerSize" type="number" min="28" max="64" />
                <div class="ai-helper-text">建议 40-56</div>
              </div>
            </div>
            <div class="ai-form-field" style="margin-top: 18px">
              <label class="ai-field-label">触发器样式</label>
              <div class="trigger-type-grid" :style="{ '--ai-chat-color': form.widgetThemeColor }">
                <button
                  type="button"
                  :class="['trigger-type-card', { active: form.widgetTriggerType === 'icon' }]"
                  @click="form.widgetTriggerType = 'icon'"
                >
                  <span class="trigger-type-preview">
                    <span class="trigger-type-icon" v-html="currentIconSvg"></span>
                  </span>
                  <span class="trigger-type-text">
                    <span class="trigger-type-name">静态图标</span>
                    <span class="trigger-type-desc">简洁轻量，加载最快</span>
                  </span>
                </button>
                <button
                  type="button"
                  :class="['trigger-type-card', { active: form.widgetTriggerType === 'pet' }]"
                  @click="form.widgetTriggerType = 'pet'"
                >
                  <span class="trigger-type-preview">
                    <img v-if="petTypePreviewImage" :style="{ imageRendering: selectedPetManifest?.imageRendering || 'auto' }" :src="petTypePreviewImage" alt="宠物预览" />
                  </span>
                  <span class="trigger-type-text">
                    <span class="trigger-type-name">交互宠物</span>
                    <span class="trigger-type-desc">会呼吸眨眼，随对话状态变换表情</span>
                  </span>
                </button>
              </div>
            </div>

            <!-- 宠物选择与生成（pet 模式） -->
            <template v-if="form.widgetTriggerType === 'pet'">
              <div class="ai-form-field" style="margin-top: 18px" v-if="petPresets.length">
                <label class="ai-field-label">内置皮肤</label>
                <div class="ai-pet-grid">
                  <button
                    v-for="preset in petPresets"
                    :key="preset.name"
                    type="button"
                    :class="['ai-pet-card', { active: form.widgetPetPreset === preset.name }]"
                    @click="selectPresetPet(preset.name)"
                  >
                    <img :src="preset.preview" :alt="preset.displayName" loading="lazy" />
                    <span>{{ preset.displayName }}</span>
                  </button>
                </div>
              </div>

              <div class="ai-form-field" style="margin-top: 18px">
                <label class="ai-field-label">我的宠物（AI 生成）</label>
                <div class="ai-pet-grid">
                  <div
                    v-for="pet in pets"
                    :key="pet.id"
                    :class="['ai-pet-card', 'ai-pet-generated-card', { active: form.widgetPetId === pet.id }]"
                  >
                    <button type="button" class="ai-pet-card-select" @click="selectGeneratedPet(pet.id)"
                      :aria-pressed="form.widgetPetId === pet.id" :title="pet.name">
                      <img :style="{ imageRendering: pet.pixelGridSize === 96 ? 'pixelated' : 'auto' }" :src="pet.images.idle" :alt="pet.name" loading="lazy" />
                      <span class="ai-pet-card-name">{{ pet.name }}</span>
                    </button>
                    <div class="ai-pet-card-actions">
                      <button type="button" class="ai-pet-card-primary" @click="openPetEditor(pet)">
                        {{ petEditActionLabel(pet) }}
                      </button>
                      <VDropdown placement="bottom-end" :distance="6">
                        <button type="button" class="ai-pet-card-more" :aria-label="`${pet.name}的更多操作`" title="更多操作">⋯</button>
                        <template #popper>
                          <div class="ai-pet-card-menu" role="menu" :aria-label="`${pet.name}的操作`">
                            <button type="button" role="menuitem" v-close-popper @click="openPetRename(pet)">改名</button>
                            <button type="button" role="menuitem" class="danger" v-close-popper @click="askDeletePet(pet)">删除</button>
                          </div>
                        </template>
                      </VDropdown>
                    </div>
                  </div>
                  <button type="button" class="ai-pet-card ai-pet-generated-card ai-pet-add" @click="openPetModal">
                    <span class="ai-pet-add-icon">+</span>
                    <span>上传照片生成</span>
                  </button>
                </div>
                <div class="ai-helper-text" v-if="!pets.length">
                  还没有自己的宠物。点击「上传照片生成」，把自家宠物或喜欢的形象变成会动的 AI 按钮（需先在「模型配置」里配好图像生成模型）。
                </div>
              </div>

              <div v-if="selectedPetManifest" class="ai-pet-avatar-settings">
                <div class="ai-section-title">聊天头像</div>
                <div class="ai-helper-text">顶部和回复使用同一张母版头像。调整脸部位置与大小，实时预览；保存配置后生效。</div>
                <div class="ai-pet-avatar-previews">
                  <div><div class="ai-pet-avatar-circle"><img :src="selectedPetManifest.images.idle" :style="petAvatarImageStyle" alt="宠物头像预览" /></div><span>顶部头像</span></div>
                  <div><div class="ai-pet-avatar-circle small"><img :src="selectedPetManifest.images.idle" :style="petAvatarImageStyle" alt="回复头像预览" /></div><span>回复头像</span></div>
                </div>
                <div class="ai-form-grid-2">
                  <label>水平位置 {{ petAvatarXPercent }}%<input class="ai-avatar-range" aria-label="头像水平位置" type="range" min="0" max="100" v-model.number="petAvatarXPercent" /></label>
                  <label>垂直位置 {{ petAvatarYPercent }}%<input class="ai-avatar-range" aria-label="头像垂直位置" type="range" min="0" max="100" v-model.number="petAvatarYPercent" /></label>
                  <label>头像缩放 {{ petAvatarZoom }}%<input class="ai-avatar-range" aria-label="头像缩放" type="range" min="118" max="667" v-model.number="petAvatarZoom" /></label>
                </div>
                <button type="button" class="ai-prompt-toggle" @click="resetPetAvatarCrop">恢复默认头像位置</button>
              </div>

              <div class="ai-form-grid-2" style="margin-top: 18px">
                <div class="ai-form-field">
                  <label class="ai-field-label">宠物尺寸 (px)</label>
                  <input class="ai-input" v-model.number="form.widgetPetSize" type="number" min="64" max="160" />
                  <div class="ai-helper-text">建议 80-120，宠物比图标大才看得见表情</div>
                </div>
                <div class="ai-form-field">
                  <label class="ai-field-label">宠物图存储策略</label>
                  <select class="ai-input ai-select" v-model="form.widgetPetStoragePolicy">
                    <option value="">自动（本地优先）</option>
                    <option v-for="p in petPolicies" :key="p.name" :value="p.name">
                      {{ p.displayName }}{{ p.local ? "（本地）" : "" }}
                    </option>
                  </select>
                  <div class="ai-helper-text">生成的宠物图上传到哪个附件策略；指定后上传失败即报错，不再回退其他策略</div>
                </div>
              </div>
              <div class="ai-option-grid" style="margin-top: 12px">
                <OptionCard v-model="form.widgetPetGreeting" title="首次访问打招呼" desc="访客首次来到站点时，宠物旁弹出欢迎气泡（每次会话一次）" />
              </div>
              <div class="ai-form-field" style="margin-top: 14px">
                <label class="ai-field-label">宠物语录</label>
                <textarea
                  class="ai-input ai-textarea"
                  v-model="form.widgetPetPhrases"
                  rows="3"
                  placeholder="有问题随时问我~&#10;点我聊聊吧！"
                ></textarea>
                <div class="ai-helper-text">鼠标悬停宠物时轮换显示，一行一条，最多 10 条；留空则不显示悬停气泡。首次打招呼使用第一条，未配置时使用欢迎语</div>
              </div>
            </template>

            <!-- 图标配置（icon 模式） -->
            <template v-else>
            <div class="ai-form-field" style="margin-top: 18px">
              <label class="ai-field-label">悬浮按钮图标</label>
              <div class="ai-icon-grid" :style="{ '--ai-chat-color': form.widgetThemeColor }">
                <button
                  v-for="icon in ICON_PRESETS"
                  :key="icon.value"
                  type="button"
                  :class="['ai-icon-grid-item', { active: form.widgetIcon === icon.value }]"
                  :title="icon.label"
                  @click="form.widgetIcon = icon.value"
                  v-html="icon.svg"
                ></button>
              </div>
              <div class="ai-helper-text">当前：{{ currentIconLabel }}</div>
            </div>
            <div class="ai-form-field" style="margin-top: 18px">
              <label class="ai-field-label">按钮形状</label>
              <div class="ai-shape-grid" :style="{ '--ai-chat-color': form.widgetThemeColor }">
                <button
                  v-for="shape in TRIGGER_SHAPES"
                  :key="shape.value"
                  type="button"
                  :class="['ai-shape-item', { active: form.widgetTriggerShape === shape.value }]"
                  :title="shape.label"
                  @click="form.widgetTriggerShape = shape.value"
                >
                  <span class="ai-shape-preview" :style="{ borderRadius: shape.radius }"></span>
                  <span class="ai-shape-label">{{ shape.label }}</span>
                </button>
              </div>
            </div>
            <div class="ai-form-field" style="margin-top: 18px">
              <label class="ai-field-label">按钮文字（留空则显示图标）</label>
              <input class="ai-input" v-model="form.widgetTriggerLabel" placeholder="如：AI" maxlength="4" />
              <div class="ai-helper-text">填写文字后图标配置失效，最多 4 个字符</div>
            </div>
            </template>
            <div class="ai-form-field" style="margin-top: 18px">
              <label class="ai-field-label">主题色</label>
              <ThemeColorPicker
                v-model="form.widgetThemeColor"
                :effective-color="form.widgetThemeColor || DEFAULTS.widgetThemeColor"
                :invalid="!widgetThemeColorValid"
              />
              <div class="ai-helper-text" :class="{ error: !widgetThemeColorValid }">
                {{ widgetThemeColorHint }}
              </div>
            </div>
            <div class="ai-card-actions">
              <VButton type="default" @click="resetFields(Object.keys(DEFAULTS))">恢复默认</VButton>
              <VButton type="primary" :disabled="saving || !widgetThemeColorValid" @click="save">{{ saving ? '保存中...' : '保存配置' }}</VButton>
            </div>
          </div>
        </SectionCard>
        </div>
      </div>

      <!-- 右侧：实时预览 -->
      <div class="chat-preview">
        <div class="widget-preview-panel">
          <div class="widget-preview-header">
            <span class="widget-preview-icon"><RiEyeLine /></span>
            <div class="widget-preview-info">
              <div class="widget-preview-title">实时预览</div>
              <div class="widget-preview-desc">配置变更实时同步，仅预览外观与交互效果</div>
            </div>
          </div>
          <div class="widget-preview-body" ref="previewBodyRef">
            <div
              class="widget-preview-stage"
              :style="{ width: stageWidth + 'px', height: stageHeight + 'px' }"
            >
              <iframe
                ref="iframeRef"
                class="ai-preview-iframe"
                :src="previewSrc"
                frameborder="0"
                :style="{ width: frameWidth + 'px', height: frameHeight + 'px', transform: `scale(${previewScale})` }"
                @load="sendPreviewConfig"
              ></iframe>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 新建宠物弹窗 -->
    <div v-if="petModalOpen" class="ai-pet-modal-mask" @click.self="closePetModal">
      <div class="ai-pet-modal">
        <div class="ai-pet-modal-title">{{ petModalTitle }}</div>
        <div class="ai-pet-modal-body">
          <div v-if="!petModelReady" class="ai-pet-model-notice" role="status" aria-live="polite">
            <strong>{{ petModelState === 'checking' ? '检查模型配置' : '生图模型尚未就绪' }}</strong>
            <p>{{ petModelMessage }}</p>
            <template v-if="petModelState === 'unavailable'">
              <RouterLink to="/console/ai-suite/models" @click="closePetModal">去配置生图模型</RouterLink>
              <button type="button" class="ai-prompt-toggle" @click="checkPetModel">重新检查</button>
            </template>
          </div>
          <div v-if="petModalPhase === 'create' || petModalPhase === 'expressions'" class="ai-pet-model-notice">
            <strong>模型使用提示</strong>
            <p>目前已使用 Qwen Image 2.0 Pro、3.0 Pro 和豆包 Seedream 5（doubao-seedream5）验证宠物生成。其他模型需支持参考图生成，效果与兼容性可能不同，建议先测试连通性并检查一张母版，再生成四种表情。</p>
          </div>
          <div v-if="expressionPet && (petModalPhase === 'expressions' || petModalPhase === 'complete')" class="ai-form-field">
            <label class="ai-field-label">生成方式</label>
            <select class="ai-input" v-model="petExpressionMode" :disabled="petJobStatus === 'pending'">
              <option value="face">仅变表情（默认）</option>
              <option value="motion" :disabled="expressionPet.style !== 'soft-3d'">表情＋轻动作</option>
            </select>
            <span class="ai-helper-text">轻动作适合精致立体的人形或拟人角色。每张新图需独立检查背景并确认；像素风暂使用仅变表情。</span>
          </div>
          <div v-if="expressionPet && (petModalPhase === 'expressions' || petModalPhase === 'complete') && (petExpressionMode === 'motion' || hasCandidates || petModalPhase === 'complete')" class="ai-candidate-panel">
            <strong>逐张生成与检查</strong>
            <p class="ai-helper-text">新图确认前不会替换当前状态。某张不满意可以只重试这一张。</p>
            <div class="ai-expression-results">
              <figure v-for="item in expressionStates" :key="item.state">
                <div class="ai-expression-result-image" v-if="expressionPet.expressionCandidates?.[item.state] || expressionPet.images[item.state]">
                  <img :src="expressionPet.expressionCandidates?.[item.state]?.imageUrl || expressionPet.images[item.state]" :alt="item.label" />
                </div>
                <figcaption>{{ item.label }} · {{ expressionPet.expressionCandidates?.[item.state] ? (expressionPet.expressionCandidates[item.state]?.editingExisting ? '编辑待保存' : '新图待确认') : (expressionPet.images[item.state] ? '当前版本' : '尚未生成') }}</figcaption>
                <div class="ai-expression-result-actions">
                  <button v-if="expressionPet.expressionCandidates?.[item.state] || expressionPet.images[item.state]" type="button" class="ai-expression-review-button" :disabled="petJobStatus === 'pending'" @click="openCandidateReview(item.state)">检查背景</button>
                  <button type="button" class="ai-expression-regenerate-button" :disabled="!petModelReady || petJobStatus === 'pending'" @click="requestSingleExpression(item.state)">重新生成</button>
                </div>
              </figure>
            </div>
          </div>
          <template v-if="petModalPhase === 'create'">
          <div class="ai-pipeline-steps">
            <span class="active">1 生成母版</span><span>2 检查背景</span><span>3 表情区域</span><span>4 生成表情</span>
          </div>
          <div class="ai-form-field">
            <label class="ai-field-label">照片</label>
            <div class="ai-pet-upload" @click="triggerPetFileSelect">
              <img v-if="petPhotoPreview" :src="petPhotoPreview" alt="照片预览" />
              <span v-else>点击选择照片<br /><small>JPEG / PNG / WebP，不超过 5MB</small></span>
            </div>
            <input
              ref="petFileInput"
              type="file"
              accept="image/jpeg,image/png,image/webp"
              style="display: none"
              @change="onPetPhotoChange"
            />
          </div>
          <div class="ai-form-field">
            <label class="ai-field-label">宠物名称</label>
            <input class="ai-input" v-model="petName" placeholder="如：小橘" maxlength="20" />
          </div>
          <div class="ai-form-field">
            <label class="ai-field-label">画风</label>
            <select class="ai-input ai-select" v-model="petStyle">
              <option value="soft-3d">精致立体（推荐）</option>
              <option value="pixel">复古像素</option>
            </select>
            <span v-if="petStyle === 'pixel'" class="ai-helper-text">自动统一像素网格与色板，建议以 96px 查看效果。</span>
          </div>
          <div class="ai-form-field">
            <label class="ai-field-label">母版补充要求 <span class="ai-optional">可选</span></label>
            <textarea
              class="ai-input ai-textarea ai-prompt-input"
              v-model="petCustomPrompt"
              maxlength="500"
              rows="3"
              placeholder="例如：保留照片中的站姿，双手自然下垂，表面是柔和布艺"
            ></textarea>
            <span class="ai-helper-text">默认保留照片中清晰可见的姿态；想改成站姿或坐姿，可在这里说明。</span>
            <div class="ai-prompt-meta"><span>系统会强制保留透明边缘、小尺寸和无地面等质量约束</span><span>{{ petCustomPrompt.length }}/500</span></div>
          </div>
          <button type="button" class="ai-prompt-toggle" @click="togglePromptPreview">
            {{ showPromptPreview ? '收起完整提示词' : '查看完整提示词' }}
          </button>
          <div v-if="showPromptPreview" class="ai-prompt-preview">
            <strong>母版最终提示词</strong>
            <div>{{ promptPreview.master || '正在生成预览…' }}</div>
          </div>
          <div class="ai-helper-text">
            先生成一张待机母版供你确认，不会立即消耗四次表情生成额度。
          </div>
          <div class="ai-upload-guidance">
            建议照片主体完整、背景简单，并让主体与背景有明显颜色差异；浅色角色不必使用浅色背景。
          </div>
          </template>

          <template v-else-if="petModalPhase === 'background' && expressionPet">
            <div class="ai-pipeline-steps">
              <span>1 生成母版</span><span class="active">2 检查背景</span><span>3 表情区域</span><span>4 生成表情</span>
            </div>
            <div class="ai-expression-help">
              {{ candidateReviewState ? (expressionPet.expressionCandidates?.[candidateReviewState]?.editingExisting ? '正在编辑当前图片的副本，保存并使用后才替换当前状态；取消编辑可保留原图。' : '独立检查这张新图的透明背景、手势及角色一致性。确认后仅替换这一状态；身体轮廓可与母版不同。') : '依次检查棋盘格、浅色和深色背景。发现脚底阴影、白斑或底座时，可按颜色选择并应用，或用橡皮擦修补；仅变表情时会复用母版透明轮廓。' }}
            </div>
            <div v-if="!candidateReviewState" class="ai-form-field">
              <label class="ai-field-label">母版补充要求</label>
              <textarea
                class="ai-input ai-textarea ai-prompt-input"
                v-model="petCustomPrompt"
                maxlength="500"
                rows="2"
                placeholder="修改后可重新生成母版"
              ></textarea>
              <div class="ai-prompt-meta"><span>新母版生成失败时会保留当前版本</span><span>{{ petCustomPrompt.length }}/500</span></div>
              <div v-if="!petPhotoFile" class="ai-helper-text">从已有宠物进入时不保留原始照片；如需重做母版，请重新打开生成流程并选择照片。</div>
            </div>
            <div class="ai-background-preview-tabs" role="group" aria-label="背景检查模式">
              <button v-for="mode in backgroundPreviewModes" :key="mode.value" type="button"
                :class="{ active: backgroundPreviewMode === mode.value }"
                @click="backgroundPreviewMode = mode.value">{{ mode.label }}</button>
            </div>
            <div class="ai-cleanup-toolbar">
              <button type="button" class="ai-cleanup-toggle" :class="{ active: cleanupMode && cleanupTool === 'color' }"
                :disabled="petJobStatus === 'pending' || candidateReviewBusy || !colorImageReady" @click="cleanupMode = true; cleanupTool = 'color'">按颜色选择</button>
              <button
                type="button"
                class="ai-cleanup-toggle"
                :class="{ active: cleanupMode && cleanupTool === 'brush' }"
                :disabled="petJobStatus === 'pending'"
                @click="toggleCleanupMode"
              >橡皮擦修补</button>
              <span>{{ cleanupTool === 'color' ? '点击残留选中相连的相近颜色；从残留处拖动框选可限制范围。红色区域将被清除。' : '在残留处拖动涂抹，避免碰到角色本体' }}</span>
            </div>
            <div class="ai-image-navigation">
              <button type="button" @click="zoomBackgroundBy(1 / 1.25)" aria-label="缩小图片">−</button>
              <span>{{ Math.round(backgroundZoom * 100) }}%</span>
              <button type="button" @click="zoomBackgroundBy(1.25)" aria-label="放大图片">＋</button>
              <button type="button" @click="resetBackgroundView">适应窗口</button>
              <button type="button" :aria-pressed="backgroundPanMode" @click="backgroundPanMode = !backgroundPanMode">{{ backgroundPanMode ? '移动中' : '移动画面' }}</button>
              <span class="ai-helper-text">滚轮/触摸板缩放，空格＋拖动移动</span>
            </div>
            <div ref="backgroundViewportRef" class="ai-background-viewport" :class="{ 'pan-mode': backgroundPanMode }" tabindex="0" aria-label="背景编辑画布" :style="{ aspectRatio: colorImageAspect }"
              @wheel.prevent="zoomBackgroundWheel" @pointerdown.capture="beginBackgroundPan">
            <div
              ref="expressionEditorRef"
              class="ai-expression-editor"
              :class="[`preview-${backgroundPreviewMode}`, { 'cleanup-active': cleanupMode, 'brush-active': cleanupMode && cleanupTool === 'brush' && !backgroundPanMode && !candidateReviewBusy && petJobStatus !== 'pending' }]"
              :style="{ aspectRatio: colorImageAspect, transform: `translate(${backgroundPan.x}px, ${backgroundPan.y}px) scale(${backgroundZoom})` }"
              @pointerdown="beginEditorPointer"
              @pointerenter="updateCleanupCursor"
              @pointermove="updateCleanupCursor"
              @pointerleave="cleanupCursor = null"
            >
              <img :style="{ imageRendering: expressionPet.pixelGridSize === 96 ? 'pixelated' : 'auto' }" :src="edgePreviewUrl || backgroundMasterUrl" :alt="expressionPet.name" draggable="false" @load="loadColorImage" />
              <canvas ref="colorOverlayRef" class="ai-color-overlay" aria-hidden="true"></canvas>
              <div v-if="cleanupCursor && cleanupMode && cleanupTool === 'brush' && !candidateReviewBusy && petJobStatus !== 'pending'"
                class="ai-cleanup-cursor" :style="cleanupStrokeStyle({ ...cleanupCursor, radius: cleanupBrushRadius })" aria-hidden="true"></div>
              <div v-if="colorDragBox" class="ai-color-drag-box" :style="colorDragBox"></div>
              <div
                v-for="(stroke, index) in cleanupStrokes"
                :key="index"
                class="ai-cleanup-mark"
                :style="cleanupStrokeStyle(stroke)"
              ></div>
            </div>
            </div>
            <div class="ai-edge-controls">
              <strong>边缘优化</strong>
              <span class="ai-helper-text">轻微调整即可；去白边可能改变半透明毛发的颜色，请在深浅背景下检查。</span>
              <label>轻微收边 <select v-model.number="edgeShrink" :disabled="candidateReviewBusy || edgePreviewBusy"><option :value="0">不收边</option><option :value="1">1 像素</option><option :value="2">2 像素</option></select></label>
              <label>去白边 {{ edgeDewhite }}% <input type="range" min="0" max="100" v-model.number="edgeDewhite" :disabled="candidateReviewBusy || edgePreviewBusy" aria-label="去白边强度" /></label>
              <div class="ai-edge-buttons">
                <button type="button" :disabled="candidateReviewBusy || edgePreviewBusy || cleanupHasMarks || petJobStatus === 'pending' || (!edgeShrink && !edgeDewhite)" @click="processEdges(false)">{{ edgePreviewBusy ? '预览中…' : '预览优化' }}</button>
                <button type="button" :disabled="!edgePreviewUrl || candidateReviewBusy || edgePreviewBusy" @click="edgePreviewUrl = ''">取消预览</button>
                <button type="button" :disabled="!edgePreviewUrl || candidateReviewBusy || edgePreviewBusy || cleanupHasMarks || petJobStatus === 'pending'" @click="processEdges(true)">应用优化</button>
              </div>
              <span v-if="edgePreviewUrl" role="status" class="ai-helper-text">正在预览，原图尚未修改。应用后可恢复本次编辑的原图。</span>
            </div>
            <div class="ai-cleanup-controls">
              <label v-if="cleanupTool === 'brush'">橡皮擦大小 <strong>{{ Math.round(cleanupBrushRadius * 200) }}%</strong>
                <input type="range" min="1" max="12" v-model.number="cleanupBrushPercent" />
              </label>
              <label v-else>颜色容差 <strong>{{ colorTolerance }}%</strong>
                <input type="range" min="0" max="60" v-model.number="colorTolerance" aria-label="颜色容差" />
              </label>
              <span>{{ colorSelectedCount ? `已选 ${colorSelectedPercent}% 像素` : (cleanupStrokes.length ? `已记录 ${cleanupStrokes.length} 个涂抹点` : '尚未选择') }}</span>
              <button type="button" class="ai-prompt-toggle" :disabled="!cleanupStrokes.length && !colorSelections.length" @click="undoCleanupStroke">撤销一步</button>
              <button type="button" class="ai-prompt-toggle" :disabled="!cleanupStrokes.length && !colorSelections.length" @click="clearCleanupStrokes">清空标记</button>
              <button type="button" class="ai-cleanup-apply" :disabled="!cleanupHasMarks || candidateReviewBusy || petJobStatus === 'pending'" @click="startBackgroundCleanup">{{ candidateReviewState ? '应用到这张图' : '应用到母版' }}</button>
            </div>
            <div class="ai-helper-text">{{ colorSelectionError || '仅选择相连区域，不会自动选择其他位置的同色像素。若选到了角色，请撤销、降低容差或拖动缩小框选范围。应用前不会修改原图。' }}</div>
            <div class="ai-background-review-actions">
              <span>状态：{{ candidateReviewState ? (expressionPet.expressionCandidates?.[candidateReviewState]?.editingExisting ? '编辑副本，保存前保持当前图' : '新图等待确认') : (isBackgroundApproved(expressionPet) ? '已确认' : '等待确认') }}</span>
              <button type="button" class="ai-prompt-toggle" :disabled="(!candidateReviewState && !expressionPet.originalMasterUrl) || candidateReviewBusy || petJobStatus === 'pending'" @click="resetBackground">{{ candidateReviewState ? (expressionPet.expressionCandidates?.[candidateReviewState]?.editingExisting ? '恢复本次编辑原图' : '恢复原始候选图') : '恢复原始母版' }}</button>
            </div>
          </template>

          <template v-else-if="petModalPhase === 'expressions' && expressionPet">
            <div class="ai-pipeline-steps">
              <span>1 生成母版</span><span>2 检查背景</span><span class="active">3 表情区域</span><span>4 生成表情</span>
            </div>
            <div class="ai-expression-help">
              {{ petExpressionMode === 'motion' ? '轻动作允许小幅手势，需逐张验收。下面五官区域仅用于眨眼，眨眼仍保持母版身体不变。' : '拖动虚线椭圆对准眼睛、眉毛和嘴巴。椭圆外的身体、轮廓与已确认透明背景会保持母版原像素。' }}
            </div>
            <div ref="expressionEditorRef" class="ai-expression-editor" @pointerdown="beginEditorPointer">
              <img :style="{ imageRendering: expressionPet.pixelGridSize === 96 ? 'pixelated' : 'auto' }" :src="backgroundMasterUrl" :alt="expressionPet.name" draggable="false" />
              <div class="ai-expression-region" :style="expressionRegionStyle"></div>
            </div>
            <div class="ai-expression-controls">
              <label>区域宽度 <strong>{{ Math.round(expressionRegion.width * 100) }}%</strong>
                <input type="range" min="10" max="90" v-model.number="expressionRegionWidthPercent" />
              </label>
              <label>区域高度 <strong>{{ Math.round(expressionRegion.height * 100) }}%</strong>
                <input type="range" min="10" max="90" v-model.number="expressionRegionHeightPercent" />
              </label>
              <label v-if="expressionPet.pixelGridSize !== 96">边缘柔化 <strong>{{ Math.round(expressionRegion.feather * 100) }}%</strong>
                <input type="range" min="2" max="30" v-model.number="expressionRegionFeatherPercent" />
              </label>
            </div>
            <div class="ai-form-field">
              <label class="ai-field-label">表情补充要求 <span class="ai-optional">可选</span></label>
              <textarea
                class="ai-input ai-textarea ai-prompt-input"
                v-model="petExpressionPrompt"
                maxlength="300"
                rows="2"
                placeholder="例如：表情克制柔和，不要露牙，不要改变面部材质"
              ></textarea>
              <div class="ai-prompt-meta"><span>{{ petExpressionMode === 'motion' ? '仅允许小幅动作，保持角色身份、服装和脚底位置' : '仅作为补充，系统仍强制只修改五官' }}</span><span>{{ petExpressionPrompt.length }}/300</span></div>
            </div>
            <button type="button" class="ai-prompt-toggle" @click="togglePromptPreview">
              {{ showPromptPreview ? '收起完整提示词' : '查看完整提示词' }}
            </button>
            <div v-if="showPromptPreview" class="ai-prompt-preview">
              <strong>母版最终提示词</strong>
              <div>{{ promptPreview.master || '正在生成预览…' }}</div>
              <strong>四种表情最终提示词</strong>
              <div v-for="(text, state) in promptPreview.expressions" :key="state"><b>{{ state }}</b>：{{ text }}</div>
            </div>
          </template>

          <template v-else-if="petModalPhase === 'complete' && expressionPet">
            <div class="ai-pipeline-steps">
              <span>1 生成母版</span><span>2 检查背景</span><span>3 表情区域</span><span class="active">4 生成表情</span>
            </div>
            <div class="ai-expression-help">{{ hasCandidates ? '新图待逐张确认，当前生效图片保留。可以关闭后继续检查。' : '四种表情已生成，可以点击完成。请检查表情是否清楚、背景是否干净。' }}</div>
            <div class="ai-helper-text">宠物图片已保存；如需在前台使用，请在外观页保存配置。</div>
          </template>

          <div v-if="petJobStatus === 'pending'" class="ai-pet-progress" role="status" aria-live="polite">
            <div class="ai-pet-progress-title"><span class="ai-pet-progress-spinner" aria-hidden="true"></span>{{ petProgressLabel }}</div>
            <div><span v-if="petJobKind !== 'cleanup'">{{ petJobKind === 'candidates' ? '已保存' : '已生成' }} {{ petProgress.completed }}/{{ petProgress.total }} 张 · </span>已用时 {{ petElapsedSeconds }} 秒</div>
            <p v-if="petProgress.stage === 'model'">{{ petElapsedSeconds >= 90 ? '模型仍在处理中，等待时间因模型而异，请勿重复提交。' : '正在等待模型返回图片，请勿重复提交。' }}</p>
            <p v-else-if="petProgress.stage === 'saving'">图片正在保存，保存完成后会更新结果。</p>
          </div>
          <div v-if="petJobStatus === 'failed'" class="ai-helper-text error">
            生成失败：{{ petJobError }}
          </div>
          <div v-if="petJobStatus === 'done' && petModalPhase !== 'complete'" class="ai-helper-text" style="color: #16a34a">
            {{ petJobKind === 'candidates' ? '新图已保存，请逐张检查背景并确认' : (petJobKind === 'cleanup' ? '母版背景已清理，请继续检查并确认' : (petJobKind === 'expressions' ? '四种表情已生成，可以点击完成' : (petJobKind === 'master-regenerate' ? '新母版已替换，请重新检查背景' : '母版生成完成，请检查透明背景'))) }}
          </div>
        </div>
        <div class="ai-card-actions">
          <VButton v-if="petModalPhase !== 'complete'" type="default" @click="closePetModal">关闭</VButton>
          <VButton
            v-if="petModalPhase === 'create'"
            type="primary"
            :disabled="!petModelReady || !petPhotoFile || petJobStatus === 'pending'"
            @click="startPetGenerate"
          >{{ petJobStatus === 'pending' ? '生成中...' : '生成待机母版' }}</VButton>
          <VButton v-if="candidateReviewState && expressionPet" type="default" :disabled="candidateReviewBusy || edgePreviewBusy" @click="returnFromBackgroundReview">{{ expressionPet.expressionCandidates?.[candidateReviewState]?.editingExisting ? '取消编辑并返回' : '返回表情列表' }}</VButton>
          <VButton
            v-if="petModalPhase === 'background' && !candidateReviewState"
            type="default"
            :disabled="!petModelReady || !petPhotoFile || petJobStatus === 'pending'"
            @click="startMasterRegenerate"
          >{{ petJobStatus === 'pending' && petJobKind === 'master-regenerate' ? '重新生成中...' : '修改要求并重新生成母版' }}</VButton>
          <VButton
            v-if="petModalPhase === 'background'"
            type="primary"
            :disabled="!expressionPet || candidateReviewBusy || edgePreviewBusy || edgePreviewUrl !== '' || cleanupHasMarks || petJobStatus === 'pending'"
            @click="approveBackground"
          >{{ candidateReviewState ? '保存并使用这张图' : '确认背景并继续' }}</VButton>
          <VButton
            v-if="petModalPhase === 'expressions'"
            type="default"
            :disabled="petJobStatus === 'pending'"
            @click="openBackgroundReview(expressionPet)"
          >重新检查背景</VButton>
          <VButton
            v-if="petModalPhase === 'expressions'"
            :type="hasPetExpressions(expressionPet) ? 'default' : 'primary'"
            :disabled="!petModelReady || !expressionPet || !isBackgroundApproved(expressionPet) || petJobStatus === 'pending'"
            @click="requestExpressionGenerate"
          >{{ petJobStatus === 'pending' ? '生成中...' : (hasPetExpressions(expressionPet) ? '重新生成四种表情' : '生成四种表情') }}</VButton>
          <VButton v-if="petModalPhase === 'complete' && expressionPet" type="default"
            @click="petModalPhase = 'expressions'">调整表情</VButton>
          <VButton v-if="petModalPhase === 'complete'" type="default"
            :disabled="!petModelReady || petJobStatus === 'pending'" @click="requestExpressionGenerate">重新生成四种表情</VButton>
          <VButton v-if="petModalPhase === 'complete' || (petModalPhase === 'expressions' && hasPetExpressions(expressionPet))"
            type="primary" :disabled="petJobStatus === 'pending'" @click="closePetModal">完成</VButton>
        </div>
      </div>
    </div>

    <VDialog
      v-model:visible="showExpressionRegenerateDialog"
      type="warning"
      :title="pendingExpressionState ? '重新生成这一张' : '生成四种表情'"
      :description="regenerationDescription"
      confirm-text="确认生成"
      cancel-text="取消"
      :on-confirm="confirmExpressionRegenerate"
    />

    <VModal v-model:visible="petRenameOpen" title="宠物改名" :width="420" :layer-closable="!petRenameBusy">
      <form id="pet-rename-form" @submit.prevent="savePetRename" class="ai-pet-rename-form">
        <label class="ai-field-label" for="pet-rename-name">宠物名称</label>
        <input id="pet-rename-name" class="ai-input" v-model="petRenameName" :disabled="petRenameBusy"
          maxlength="40" autocomplete="off" placeholder="填写新的宠物名称" />
        <div class="ai-prompt-meta"><span>保存后立即生效，无需重新生成图片</span><span>{{ petRenameLength }}/20</span></div>
        <p v-if="petRenameError" class="ai-pet-rename-error" role="alert">{{ petRenameError }}</p>
      </form>
      <template #footer>
        <div class="ai-pet-rename-actions">
          <VButton :disabled="petRenameBusy" @click="petRenameOpen = false">取消</VButton>
          <VButton type="primary" :disabled="!petRenameValid || petRenameBusy" @click="savePetRename">{{ petRenameBusy ? '保存中...' : '保存名称' }}</VButton>
        </div>
      </template>
    </VModal>

    <!-- 删除宠物二次确认（VDialog 标准结构，与问答记录/智能体删除弹窗同范式） -->
    <VDialog
      v-model:visible="showPetDeleteDialog"
      type="warning"
      title="删除宠物"
      :description="petDeleteDescription"
      confirm-type="danger"
      confirm-text="删除"
      cancel-text="取消"
      :on-confirm="confirmDeletePet"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, onMounted, onBeforeUnmount, watch } from "vue";
import { Toast, VButton, VDialog, VModal, VDropdown, vClosePopper } from "@halo-dev/components";
import { selectSimilarPixels, selectionRuns, type ColorSelection } from "../utils/pet-color-selection.mjs";
import { saveGroup, loadGroup } from "../utils/config";
import { ICON_PRESETS, TRIGGER_SHAPES } from "../utils/trigger-icons";
import SectionCard from "../components/SectionCard.vue";
import OptionCard from "../components/OptionCard.vue";
import ThemeColorPicker from "../components/ThemeColorPicker.vue";
import RiPaletteLine from "~icons/ri/palette-line";
import RiEyeLine from "~icons/ri/eye-line";

// 本页只持有 chat 配置组里的「外观」字段；对话类字段（欢迎语/快捷问题等）
// 归「对话行为」页管理，保存时由 saveGroup 合并写入，互不覆盖。
// 预览 iframe 需要那些对话字段才能完整还原访客端，加载时从整组配置里读出存这里。
const previewExtras = ref({
  welcome: "Hi! 有什么想了解的？",
  shortcuts: [] as { label: string; query: string; icon: string; enabled: boolean }[],
  allowGuest: true,
  allowVisitorReasoning: true,
  reasoningDefaultEnabled: false,
});

const DEFAULTS = {
  widgetPosition: "right-bottom",
  widgetThemeColor: "#5387C4",
  widgetIcon: "ri-chat-3-line",
  widgetTriggerSize: 35,
  widgetTriggerLabel: "AI",
  widgetTheme: "auto",
  widgetWidth: 400,
  widgetHeight: 600,
  widgetTriggerAlign: "auto",
  widgetTriggerOffsetY: 125,
  widgetTriggerOffsetX: 17,
  widgetTriggerShape: "square",
  widgetTriggerType: "icon",
  widgetPetPreset: "",
  widgetPetId: "",
  widgetPetSize: 96,
  widgetPetGreeting: true,
  widgetPetPhrases: "",
  widgetPetStoragePolicy: "",
  widgetPetAvatarCrops: "{}",
};

const form = reactive({ ...DEFAULTS });
const saving = ref(false);
const saveMsg = ref("");
const saveOk = ref(false);

// 当前选中图标的中文名称（显示在选择器下方辅助说明）
const currentIconLabel = computed(
  () => ICON_PRESETS.find((i) => i.value === form.widgetIcon)?.label || "星光（默认）"
);

/** 触发器样式卡片用：当前选中图标的真实 SVG（与访客端同源） */
const currentIconSvg = computed(
  () => ICON_PRESETS.find((i) => i.value === form.widgetIcon)?.svg || ICON_PRESETS[0]?.svg || ""
);

/** 触发器样式卡片用：当前选中宠物的 idle 图；未选择时回退第一个内置皮肤 */
const petTypePreviewImage = computed(() => {
  if (form.widgetPetId) {
    const pet = pets.value.find(p => p.id === form.widgetPetId);
    if (pet?.images?.idle) return pet.images.idle;
  }
  if (form.widgetPetPreset) {
    const preset = petPresets.value.find(p => p.name === form.widgetPetPreset);
    if (preset?.preview) return preset.preview;
  }
  return petPresets.value[0]?.preview || "";
});

const widgetThemeColorValid = computed(() => {
  const value = form.widgetThemeColor.trim();
  return /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(value);
});

const widgetThemeColorHint = computed(() => {
  if (!widgetThemeColorValid.value) return "请输入合法 HEX 色值，例如 #4F46E5。";
  return "访客问答浮窗、默认搜索弹框和默认脑图区块会使用这个主题色。";
});

// ===== 实时预览：通过 postMessage 将配置变化推送到 iframe =====
// 带时间戳防止浏览器缓存旧版 embed.html 及其引用的 chat-widget.js，
// 保证后台预览总是渲染最新部署的访客端代码（图标随配置实时联动）
const previewSrc =
  window.location.origin +
  "/plugins/ai-suite/assets/res/embed.html?ai-preview=1&_t=" +
  Date.now();

/** 预览把 iframe 当作一个「迷你页面」：宽度 = 浮窗宽度 + 页面留白，
 *  且不小于 500px 以避开 widget 的移动端全屏断点（≤480px），
 *  保证预览的始终是桌面形态；iframe 按真实尺寸渲染后经 transform 缩放适配卡片。 */
const previewBodyRef = ref<HTMLElement | null>(null);
const previewBodyWidth = ref(0);
const frameWidth = computed(() => Math.max((form.widgetWidth || 400) + 48, 500));
// 面板下方为触发器按钮预留空间（bottom = 按钮距底 + 按钮尺寸 + 8px 间距）
const frameHeight = computed(() => {
  const triggerSize = form.widgetTriggerType === "pet" ? form.widgetPetSize : form.widgetTriggerSize;
  const bottomReserve = (form.widgetTriggerOffsetY || 17) + (triggerSize || 40) + 8;
  return Math.min((form.widgetHeight || 600) + bottomReserve + 32, 960);
});
const previewScale = computed(() => {
  if (!previewBodyWidth.value) return 1;
  return Math.min(1, previewBodyWidth.value / frameWidth.value);
});
const stageWidth = computed(() => Math.round(frameWidth.value * previewScale.value));
const stageHeight = computed(() => Math.round(frameHeight.value * previewScale.value));

let previewRO: ResizeObserver | null = null;
const iframeRef = ref<HTMLIFrameElement | null>(null);

// ===== AI 贴纸宠物 =====

type PetRecord = {
  id: string;
  name: string;
  style: string;
  pixelGridSize?: number;
  createdAt: number;
  images: Record<string, string>;
  customPrompt?: string;
  promptSnapshot?: string;
  expressionPrompt?: string;
  expressionPromptSnapshots?: Record<string, string>;
  backgroundCleanupStrokes?: EraseStroke[];
  originalMasterUrl?: string;
  cleanedMasterUrl?: string;
  backgroundStatus?: "pending" | "approved";
  cleanupRevision?: number;
  backgroundApprovedAt?: number;
  expressionRegion?: ExpressionRegion;
  expressionMode?: "face" | "motion";
  publishedVersion?: { name: string; images: Record<string, string>; style: string; pixelGridSize?: number; expressionRegion?: ExpressionRegion };
  expressionCandidates?: Record<string, { editingExisting?: boolean; imageUrl: string; originalUrl: string; mode: "face" | "motion"; masterUrl: string; masterRevision: number }>;

};

type EraseStroke = { x: number; y: number; radius: number };

type ExpressionRegion = {
  centerX: number;
  centerY: number;
  width: number;
  height: number;
  feather: number;
};

type PetPreset = {
  name: string;
  displayName: string;
  preview: string;
  manifest: { name: string; images: Record<string, string>; imageRendering?: "auto" | "pixelated" };
};

const PET_API = "/apis/console.api.ai-suite.halo.run/v1alpha1/pets";
const pets = ref<PetRecord[]>([]);
const petPresets = ref<PetPreset[]>([]);
/** 附件存储策略清单（本地优先排序），供宠物图存储策略下拉 */
const petPolicies = ref<{ name: string; displayName: string; local: boolean }[]>([]);
const petModalOpen = ref(false);
const petModelState = ref<"checking" | "ready" | "unavailable">("checking");
const petModelMessage = ref("");
const petModelReady = computed(() => petModelState.value === "ready");
let petModelCheckVersion = 0;
async function checkPetModel() {
  const version = ++petModelCheckVersion;
  petModelState.value = "checking";
  petModelMessage.value = "正在检查生图模型配置…";
  try {
    const { ok, data } = await petFetch(PET_API + "/model-status");
    if (version !== petModelCheckVersion || !petModalOpen.value) return;
    if (!ok || typeof data.available !== "boolean") throw new Error("check failed");
    petModelState.value = data.available ? "ready" : "unavailable";
    petModelMessage.value = data.message;
  } catch {
    if (version !== petModelCheckVersion || !petModalOpen.value) return;
    petModelState.value = "unavailable";
    petModelMessage.value = "暂时无法检查生图模型，请检查配置或稍后重新检查。";
  }
}

const petPhotoFile = ref<File | null>(null);
const petPhotoPreview = ref("");
const petName = ref("");
const petRenameOpen = ref(false);
const petRenameBusy = ref(false);
const petRenameName = ref("");
const petRenameError = ref("");
const petRenameTarget = ref<PetRecord | null>(null);
const petRenameLength = computed(() => Array.from(petRenameName.value.trim()).length);
const petRenameValid = computed(() => petRenameLength.value > 0 && petRenameLength.value <= 20
  && !/[\u0000-\u001f\u007f-\u009f]/.test(petRenameName.value.trim())
  && petRenameName.value.trim() !== petRenameTarget.value?.name);

function openPetRename(pet: PetRecord) {
  if (petRenameBusy.value) return;
  petRenameTarget.value = pet;
  petRenameName.value = pet.name;
  petRenameError.value = "";
  petRenameOpen.value = true;
}

async function savePetRename() {
  const pet = petRenameTarget.value;
  if (!pet || petRenameBusy.value || !petRenameValid.value) return;
  petRenameBusy.value = true;
  petRenameError.value = "";
  try {
    const {data} = await petFetch(`${PET_API}/${pet.id}/rename`, {
      method: "POST", headers: {"Content-Type": "application/json"},
      body: JSON.stringify({name: petRenameName.value.trim()}),
    });
    if (!data.success) throw new Error(data.message || "改名失败");
    const updated = data.pet as PetRecord;
    pets.value = pets.value.map(item => item.id === pet.id ? updated : item);
    if (expressionPet.value?.id === pet.id) {
      expressionPet.value.name = updated.name;
      if (expressionPet.value.publishedVersion) expressionPet.value.publishedVersion.name = updated.name;
    }
    petRenameOpen.value = false;
    Toast.success("宠物名称已更新");
  } catch (error: any) {
    petRenameError.value = error.message || "改名失败，请重试";
  } finally { petRenameBusy.value = false; }
}
const petStyle = ref("soft-3d");
const petCustomPrompt = ref("");
const petExpressionPrompt = ref("");
const showPromptPreview = ref(false);
const promptPreview = reactive<{ master: string; expressions: Record<string, string> }>({
  master: "",
  expressions: {},
});
const petModalPhase = ref<"create" | "background" | "expressions" | "complete">("create");
const expressionPet = ref<PetRecord | null>(null);
const showExpressionRegenerateDialog = ref(false);
const petExpressionMode = ref<"face" | "motion">("face");
const pendingExpressionState = ref<string | null>(null);
const candidateReviewState = ref<string | null>(null);
const edgeShrink = ref(0);
const edgeDewhite = ref(0);
const edgePreviewUrl = ref("");
const edgePreviewBusy = ref(false);
let edgePreviewEpoch = 0;
const backgroundViewportRef = ref<HTMLElement | null>(null);
const backgroundZoom = ref(1);
const backgroundPan = reactive({x: 0, y: 0});
const backgroundPanMode = ref(false);
let backgroundSpaceDown = false;
let backgroundPanStart: {x: number; y: number; px: number; py: number} | null = null;

function resetBackgroundView() { backgroundZoom.value = 1; backgroundPan.x = 0; backgroundPan.y = 0; cleanupCursor.value = null; }
function setBackgroundZoom(next: number, x: number, y: number) {
  next = Math.max(0.5, Math.min(6, next));
  const ratio = next / backgroundZoom.value;
  backgroundPan.x = x - (x - backgroundPan.x) * ratio;
  backgroundPan.y = y - (y - backgroundPan.y) * ratio;
  backgroundZoom.value = next; cleanupCursor.value = null;
}
function zoomBackgroundBy(factor: number) {
  const rect = backgroundViewportRef.value?.getBoundingClientRect();
  if (rect) setBackgroundZoom(backgroundZoom.value * factor, rect.width / 2, rect.height / 2);
}
function zoomBackgroundWheel(event: WheelEvent) {
  if (cleanupDrawing || colorPointerStart || backgroundPanStart) return;
  const rect = backgroundViewportRef.value?.getBoundingClientRect();
  if (!rect) return;
  const delta = event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? rect.height : 1);
  setBackgroundZoom(backgroundZoom.value * Math.exp(-Math.max(-120, Math.min(120, delta)) * 0.003), event.clientX - rect.left, event.clientY - rect.top);
}
function backgroundKeyDown(event: KeyboardEvent) {
  if (event.code !== "Space" || petModalPhase.value !== "background" || !petModalOpen.value || (event.target as HTMLElement)?.closest?.('input,textarea,select,button,[contenteditable]')) return;
  event.preventDefault(); backgroundSpaceDown = true;
}
function backgroundKeyUp(event: KeyboardEvent) { if (event.code === "Space") backgroundSpaceDown = false; }
function backgroundBlur() { backgroundSpaceDown = false; endBackgroundPan(); }
function beginBackgroundPan(event: PointerEvent) {
  (event.currentTarget as HTMLElement)?.focus?.({preventScroll:true});
  if ((!backgroundPanMode.value && !backgroundSpaceDown && event.button !== 1) || candidateReviewBusy.value) return;
  event.preventDefault(); event.stopPropagation(); cleanupCursor.value = null;
  backgroundPanStart = {x:event.clientX, y:event.clientY, px:backgroundPan.x, py:backgroundPan.y};
  window.addEventListener("pointermove", moveBackgroundPan);
  window.addEventListener("pointerup", endBackgroundPan, {once:true});
  window.addEventListener("pointercancel", endBackgroundPan, {once:true});
}
function moveBackgroundPan(event: PointerEvent) {
  if (!backgroundPanStart) return;
  const rect = backgroundViewportRef.value?.getBoundingClientRect();
  const limitX = (rect?.width || 320) * backgroundZoom.value;
  const limitY = (rect?.height || 320) * backgroundZoom.value;
  backgroundPan.x = Math.max(-limitX, Math.min(limitX, backgroundPanStart.px + event.clientX - backgroundPanStart.x));
  backgroundPan.y = Math.max(-limitY, Math.min(limitY, backgroundPanStart.py + event.clientY - backgroundPanStart.y));
}
function endBackgroundPan() {
  backgroundPanStart = null;
  window.removeEventListener("pointermove", moveBackgroundPan);
  window.removeEventListener("pointerup", endBackgroundPan);
  window.removeEventListener("pointercancel", endBackgroundPan);
}
function cancelEdgePreview() { ++edgePreviewEpoch; edgePreviewUrl.value = ""; edgePreviewBusy.value = false; }
watch([edgeShrink, edgeDewhite], cancelEdgePreview);

const candidateReviewBusy = ref(false);
const hasCandidates = computed(() => Object.keys(expressionPet.value?.expressionCandidates || {}).length > 0);
const regenerationDescription = computed(() => pendingExpressionState.value
  ? `将调用模型重新生成「${expressionStates.find(s => s.state === pendingExpressionState.value)?.label}」，可能产生一次生图费用。新图检查确认后才替换当前版本。`
  : petExpressionMode.value === "motion"
    ? "将调用模型生成四张表情与轻动作图片，可能产生四次生图费用。各图检查确认后才替换当前版本。"
    : "将重新调用模型生成四种表情，可能产生费用。成功后会替换当前表情，是否继续？");

const expressionStates = [
  { state: "blink", label: "眨眼" },
  { state: "happy", label: "开心" },
  { state: "sad", label: "委屈" },
  { state: "thinking", label: "思考" },
];
const expressionEditorRef = ref<HTMLElement | null>(null);
const expressionRegion = reactive<ExpressionRegion>({
  centerX: 0.5,
  centerY: 0.35,
  width: 0.48,
  height: 0.38,
  feather: 0.12,
});
const cleanupMode = ref(false);
const cleanupTool = ref<"color" | "brush">("color");
const colorTolerance = ref(12);
const colorOverlayRef = ref<HTMLCanvasElement | null>(null);
const colorImageReady = ref(false);
const colorImageAspect = ref("1");
const colorSelectionError = ref("");
const colorSelections = ref<ColorSelection[]>([]);
const colorSelectedCount = ref(0);
const cleanupHistory: ("brush" | "color")[] = [];
let colorImageData: ImageData | null = null;
let colorMask = new Uint8Array(0);
let colorPointerStart: {x: number; y: number; clientX: number; clientY: number} | null = null;
const colorDragBox = ref<Record<string, string> | null>(null);
const colorSelectedPercent = computed(() => colorImageData ? (100 * colorSelectedCount.value / (colorImageData.width * colorImageData.height)).toFixed(1) : "0");
const cleanupHasMarks = computed(() => cleanupStrokes.value.length > 0 || colorSelectedCount.value > 0);

const cleanupStrokes = ref<EraseStroke[]>([]);
const cleanupBrushRadius = ref(0.04);
const cleanupCursor = ref<{ x: number; y: number } | null>(null);
const petJobId = ref("");
const petJobStatus = ref<"" | "pending" | "done" | "failed">("");
const petJobError = ref("");
const petProgress = reactive({ stage: "preparing", currentState: "", completed: 0, total: 1 });
const petElapsedSeconds = ref(0);
let petElapsedTimer: ReturnType<typeof setInterval> | null = null;
let petElapsedBase = 0;
let petElapsedSyncedAt = 0;
const petProgressLabel = computed(() => {
  if (petJobKind.value === "cleanup") return "正在本地清理背景";
  const label = expressionStates.find(item => item.state === petProgress.currentState)?.label
    || ((petJobKind.value === "master" || petJobKind.value === "master-regenerate") ? "母版" : "图片");
  const stages: Record<string, string> = { preparing: "准备参考图", model: "等待模型生成", processing: "下载图片并处理背景", saving: "保存图片", "frame-complete": "图片处理完成" };
  const stage = stages[petProgress.stage] || "正在处理";
  const position = petProgress.total > 1 && petProgress.stage !== "saving"
    ? ` · 第 ${Math.min(petProgress.completed + (petProgress.stage === "frame-complete" ? 0 : 1), petProgress.total)}/${petProgress.total} 张` : "";
  return `${stage}：${label}${position}`;
});
watch(petJobStatus, status => {
  if (petElapsedTimer) clearInterval(petElapsedTimer);
  petElapsedTimer = null;
  if (status !== "pending") return;
  Object.assign(petProgress, { stage: "preparing", currentState: "", completed: 0,
    total: petJobKind.value === "expressions" || (petJobKind.value === "candidates" && !pendingExpressionState.value) ? 4 : 1 });
  petElapsedBase = 0; petElapsedSyncedAt = Date.now(); petElapsedSeconds.value = 0;
  petElapsedTimer = setInterval(() => {
    petElapsedSeconds.value = petElapsedBase + Math.max(0, Math.floor((Date.now() - petElapsedSyncedAt) / 1000));
  }, 1000);
}, { flush: "sync" });

const petJobKind = ref<"master" | "master-regenerate" | "expressions" | "cleanup" | "candidates">("master");
const petFileInput = ref<HTMLInputElement | null>(null);
let petJobTimer: ReturnType<typeof setTimeout> | null = null;
let petPollingEpoch = 0;
let cleanupDrawing = false;
const backgroundPreviewMode = ref<"checker" | "light" | "dark">("checker");
const backgroundPreviewModes = [
  { value: "checker" as const, label: "棋盘格" },
  { value: "light" as const, label: "浅色" },
  { value: "dark" as const, label: "深色" },
];

const petModalTitle = computed(() => {
  if (petModalPhase.value === "create") return "生成 AI 宠物母版";
  if (petModalPhase.value === "background") {
    return candidateReviewState.value
      ? `检查「${expressionStates.find(s => s.state === candidateReviewState.value)?.label}」的背景`
      : `检查「${expressionPet.value?.name || "宠物"}」的母版背景`;
  }
  if (petModalPhase.value === "complete") return `查看「${expressionPet.value?.name || "宠物"}」的表情`;
  return `设置「${expressionPet.value?.name || "宠物"}」的表情与动作`;
});

const backgroundMasterUrl = computed(() => {
  const pet = expressionPet.value;
  const url = candidateReviewState.value
    ? pet?.expressionCandidates?.[candidateReviewState.value]?.imageUrl || ""
    : pet?.images?.idle || "";
  if (!url) return "";
  const separator = url.includes("?") ? "&" : "?";
  return `${url}${separator}pet-revision=${pet?.cleanupRevision || 0}`;
});

watch([petModalPhase, candidateReviewState, petModalOpen], () => {
  cancelEdgePreview(); resetBackgroundView(); endBackgroundPan(); backgroundPanMode.value = false; backgroundSpaceDown = false;
});

async function processEdges(apply: boolean) {
  const pet = expressionPet.value;
  if (!pet || candidateReviewBusy.value || edgePreviewBusy.value || cleanupHasMarks.value || petJobStatus.value === 'pending') return;
  const state = candidateReviewState.value;
  const url = state ? pet.expressionCandidates?.[state]?.imageUrl : pet.images.idle;
  if (!url || (!edgeShrink.value && !edgeDewhite.value)) return;
  const epoch = ++edgePreviewEpoch;
  if (apply) candidateReviewBusy.value = true; else edgePreviewBusy.value = true;
  try {
    const {data} = await petFetch(`${PET_API}/${pet.id}/background/edge-${apply ? 'apply' : 'preview'}`, {
      method: 'POST', headers: {'Content-Type':'application/json'},
      body: JSON.stringify({state, url, revision:pet.cleanupRevision || 0, shrink:edgeShrink.value, dewhite:edgeDewhite.value}),
    });
    if (!data.success) throw new Error(data.message || '边缘优化失败');
    if (epoch !== edgePreviewEpoch || expressionPet.value?.id !== pet.id || candidateReviewState.value !== state) return;
    if (apply) {
      edgePreviewUrl.value = ''; expressionPet.value = data.pet;
      pets.value = pets.value.map(item => item.id === pet.id ? data.pet : item);
      clearCleanupStrokes(); Toast.success('边缘优化已应用，请检查后确认使用');
    } else edgePreviewUrl.value = data.image;
  } catch (error: any) { if (epoch === edgePreviewEpoch) Toast.error(error.message || '边缘优化失败'); }
  finally { if (epoch === edgePreviewEpoch) edgePreviewBusy.value = false; if (apply) candidateReviewBusy.value = false; }
}

const expressionRegionStyle = computed(() => ({
  left: `${expressionRegion.centerX * 100}%`,
  top: `${expressionRegion.centerY * 100}%`,
  width: `${expressionRegion.width * 100}%`,
  height: `${expressionRegion.height * 100}%`,
}));

const expressionRegionWidthPercent = computed({
  get: () => Math.round(expressionRegion.width * 100),
  set: (value: number) => { expressionRegion.width = value / 100; },
});
const expressionRegionHeightPercent = computed({
  get: () => Math.round(expressionRegion.height * 100),
  set: (value: number) => { expressionRegion.height = value / 100; },
});
const expressionRegionFeatherPercent = computed({
  get: () => Math.round(expressionRegion.feather * 100),
  set: (value: number) => { expressionRegion.feather = value / 100; },
});
const cleanupBrushPercent = computed({
  get: () => Math.round(cleanupBrushRadius.value * 200),
  set: (value: number) => { cleanupBrushRadius.value = value / 200; },
});

watch([petStyle, petCustomPrompt, petExpressionPrompt, petExpressionMode], () => {
  if (!showPromptPreview.value) return;
  showPromptPreview.value = false;
  promptPreview.master = "";
  promptPreview.expressions = {};
});

/** 宠物 API 安全请求：识别登录过期（fetch 自动跟随 302 拿到 /login 的 HTML）
 *  与非 JSON 响应，避免「Unexpected token '<'」这类难懂的原始解析错误 */
async function petFetch(url: string, init?: RequestInit): Promise<{ ok: boolean; data: any }> {
  const resp = await fetch(url, init);
  if (resp.redirected && resp.url.includes("/login")) {
    throw new Error("登录状态已过期，请刷新页面重新登录");
  }
  const contentType = resp.headers.get("content-type") || "";
  if (!contentType.includes("application/json")) {
    throw new Error(resp.ok ? "服务返回了非 JSON 响应" : `请求失败（HTTP ${resp.status}）`);
  }
  return { ok: resp.ok, data: await resp.json() };
}

function triggerPetFileSelect() {
  petFileInput.value?.click();
}

async function loadPets() {
  try {
    const [petsResp, presetsResp, policiesResp] = await Promise.all([
      petFetch(PET_API + "/list"),
      petFetch(PET_API + "/presets"),
      petFetch(PET_API + "/policies"),
    ]);
    if (petsResp.ok) {
      pets.value = petsResp.data.pets || [];
    }
    if (presetsResp.ok) {
      petPresets.value = presetsResp.data.presets || [];
    }
    if (policiesResp.ok) {
      petPolicies.value = policiesResp.data.policies || [];
    }
  } catch { /* 列表加载失败不阻塞表单 */ }
}

/** 当前选中宠物的 manifest（用于实时预览；与 widget-config 下发的结构一致） */
const selectedPetManifest = computed(() => {
  if (form.widgetTriggerType !== "pet") return null;
  if (form.widgetPetId) {
    const pet = pets.value.find(p => p.id === form.widgetPetId);
    const version = pet && (pet.publishedVersion || (isBackgroundApproved(pet) ? pet : null));
    if (version) return { name: version.name, images: version.images, avatarCrop: petAvatarCrop.value, imageRendering: (version.pixelGridSize === 96 && version.style === "pixel" ? "pixelated" : "auto") as "pixelated" | "auto" };
  }
  if (form.widgetPetPreset) {
    const preset = petPresets.value.find(p => p.name === form.widgetPetPreset);
    if (preset) return { ...preset.manifest, avatarCrop: petAvatarCrop.value };
  }
  return null;
});

type AvatarCrop = { centerX: number; centerY: number; size: number };
const petAvatarKey = computed(() => form.widgetPetId ? `pet:${form.widgetPetId}` : `preset:${form.widgetPetPreset}`);
function normalizeAvatarCrop(crop: AvatarCrop): AvatarCrop {
  const size = Number.isFinite(crop.size) ? Math.max(0.15, Math.min(0.85, crop.size)) : 0.52;
  const bound = (value: number, fallback: number) => Math.max(size / 2, Math.min(1 - size / 2, Number.isFinite(value) ? value : fallback));
  return { centerX: bound(crop.centerX, 0.5), centerY: bound(crop.centerY, 0.34), size };
}
function readAvatarCrops(): Record<string, AvatarCrop> {
  try { const parsed = JSON.parse(form.widgetPetAvatarCrops || "{}"); return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {}; } catch { return {}; }
}
const defaultPetAvatarCrop = computed(() => {
  const pet = pets.value.find(p => p.id === form.widgetPetId);
  const region = (pet?.publishedVersion || (isBackgroundApproved(pet || null) ? pet : null))?.expressionRegion;
  let crop = { centerX: region?.centerX ?? 0.5, centerY: region?.centerY ?? 0.34, size: region ? Math.max(region.width, region.height) * 1.3 : 0.52 };
  if (!form.widgetPetId) {
    const presets: Record<string, AvatarCrop> = { "cream-cat": { centerX: 0.5, centerY: 0.36, size: 0.64 }, "mint-robot": { centerX: 0.5, centerY: 0.35, size: 0.56 }, "star-sprite": { centerX: 0.5, centerY: 0.38, size: 0.64 } };
    crop = presets[form.widgetPetPreset] || crop;
  }
  return normalizeAvatarCrop(crop);
});
const petAvatarCrop = computed(() => normalizeAvatarCrop({ ...defaultPetAvatarCrop.value, ...readAvatarCrops()[petAvatarKey.value] }));
function updatePetAvatarCrop(change: Partial<AvatarCrop>) {
  const crops = readAvatarCrops();
  crops[petAvatarKey.value] = normalizeAvatarCrop({ ...petAvatarCrop.value, ...change });
  form.widgetPetAvatarCrops = JSON.stringify(crops);
}
function resetPetAvatarCrop() {
  const crops = readAvatarCrops(); delete crops[petAvatarKey.value]; form.widgetPetAvatarCrops = JSON.stringify(crops);
}
const petAvatarXPercent = computed({ get: () => Math.round(petAvatarCrop.value.centerX * 100), set: (value: number) => updatePetAvatarCrop({ centerX: value / 100 }) });
const petAvatarYPercent = computed({ get: () => Math.round(petAvatarCrop.value.centerY * 100), set: (value: number) => updatePetAvatarCrop({ centerY: value / 100 }) });
const petAvatarZoom = computed({ get: () => Math.round(100 / petAvatarCrop.value.size), set: (value: number) => updatePetAvatarCrop({ size: 100 / value }) });
const petAvatarImageStyle = computed(() => {
  const crop = petAvatarCrop.value;
  return { width: `${100 / crop.size}%`, height: `${100 / crop.size}%`, left: `${(0.5 - crop.centerX / crop.size) * 100}%`, top: `${(0.5 - crop.centerY / crop.size) * 100}%`, imageRendering: selectedPetManifest.value?.imageRendering || "auto" };
});

function selectGeneratedPet(id: string) {
  const pet = pets.value.find(item => item.id === id);
  if (pet && !isBackgroundApproved(pet)) {
    openBackgroundReview(pet);
    Toast.warning("请先检查并确认母版背景");
    return;
  }
  form.widgetPetId = form.widgetPetId === id ? "" : id;
  if (form.widgetPetId) form.widgetPetPreset = "";
}

function selectPresetPet(name: string) {
  form.widgetPetPreset = form.widgetPetPreset === name ? "" : name;
  if (form.widgetPetPreset) form.widgetPetId = "";
}

function openPetModal() {
  revokePetPhotoPreview();
  petPhotoFile.value = null;
  petPhotoPreview.value = "";
  petName.value = "";
  petStyle.value = "soft-3d";
  petCustomPrompt.value = "";
  petExpressionPrompt.value = "";
  showPromptPreview.value = false;
  promptPreview.master = "";
  promptPreview.expressions = {};
  petModalPhase.value = "create";
  candidateReviewState.value = null;
  petExpressionMode.value = "face";
  expressionPet.value = null;
  petJobId.value = "";
  petJobStatus.value = "";
  petJobError.value = "";
  petJobKind.value = "master";
  cleanupMode.value = false;
  clearCleanupStrokes();
  backgroundPreviewMode.value = "checker";
  if (petFileInput.value) petFileInput.value.value = "";
  petModalOpen.value = true;
  void checkPetModel();
}

function closePetModal() {
  if (candidateReviewBusy.value || edgePreviewBusy.value) return;
  const pet = expressionPet.value, state = candidateReviewState.value;
  if (pet && state && pet.expressionCandidates?.[state]?.editingExisting) {
    void discardExistingEdit(pet, state).catch(error => Toast.error(error.message || '取消编辑失败，副本仍可继续检查'));
  }
  showExpressionRegenerateDialog.value = false;
  candidateReviewState.value = null;
  endEditorPointer();
  petModalOpen.value = false;
  ++petModelCheckVersion;
  revokePetPhotoPreview();
}

function revokePetPhotoPreview() {
  if (petPhotoPreview.value.startsWith("blob:")) URL.revokeObjectURL(petPhotoPreview.value);
  petPhotoPreview.value = "";
}

function onPetPhotoChange(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  if (!["image/jpeg", "image/png", "image/webp"].includes(file.type)) {
    Toast.error("仅支持 JPEG / PNG / WebP 照片");
    return;
  }
  if (file.size > 5 * 1024 * 1024) {
    Toast.error("照片不能超过 5MB");
    return;
  }
  revokePetPhotoPreview();
  petPhotoFile.value = file;
  petPhotoPreview.value = URL.createObjectURL(file);
  if (!petName.value) petName.value = file.name.replace(/\.[^.]+$/, "");
}

async function startPetGenerate() {
  if (!petModelReady.value || petJobStatus.value === "pending") return;
  if (!petPhotoFile.value) {
    Toast.error("请先选择一张照片");
    return;
  }
  const body = new FormData();
  body.append("file", petPhotoFile.value);
  body.append("name", petName.value);
  body.append("style", petStyle.value);
  body.append("customPrompt", petCustomPrompt.value);
  // 两阶段流程：先只生成母版，确认表情区域后再消耗四次表情调用。
  body.append("withExpressions", "false");
  try {
    const { data } = await petFetch(PET_API + "/generate", { method: "POST", body });
    if (!data.success) {
      Toast.error(data.message || "提交生成任务失败");
      return;
    }
    petJobId.value = data.jobId;
    petJobStatus.value = "pending";
    petJobError.value = "";
    petJobKind.value = "master";
    pollPetJob();
  } catch (e: any) {
    Toast.error("提交生成任务失败：" + e.message);
  }
}

async function togglePromptPreview() {
  showPromptPreview.value = !showPromptPreview.value;
  if (!showPromptPreview.value) return;
  promptPreview.master = "";
  promptPreview.expressions = {};
  try {
    const { data } = await petFetch(PET_API + "/prompt-preview", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        style: petStyle.value,
        customPrompt: petCustomPrompt.value,
        expressionPrompt: petExpressionPrompt.value,
        mode: petExpressionMode.value,
      }),
    });
    if (!data.success) throw new Error(data.message || "预览失败");
    promptPreview.master = data.prompts?.master || "";
    promptPreview.expressions = data.prompts?.expressions || {};
  } catch (e: any) {
    showPromptPreview.value = false;
    Toast.error("获取完整提示词失败：" + (e.message || "未知错误"));
  }
}

async function startMasterRegenerate() {
  if (!petModelReady.value || petJobStatus.value === "pending") return;
  const pet = expressionPet.value;
  if (!pet || !petPhotoFile.value) {
    Toast.error("本次会话中没有原始照片，请重新打开生成流程");
    return;
  }
  const body = new FormData();
  body.append("file", petPhotoFile.value);
  body.append("name", petName.value || pet.name);
  body.append("style", petStyle.value || pet.style);
  body.append("customPrompt", petCustomPrompt.value);
  petJobStatus.value = "pending";
  petJobError.value = "";
  petJobKind.value = "master-regenerate";
  try {
    const { data } = await petFetch(PET_API + "/" + pet.id + "/regenerate-master", {
      method: "POST",
      body,
    });
    if (!data.success) {
      petJobStatus.value = "failed";
      petJobError.value = data.message || "提交失败";
      return;
    }
    petJobId.value = data.jobId;
    pollPetJob();
  } catch (e: any) {
    petJobStatus.value = "failed";
    petJobError.value = e.message || "提交失败";
  }
}

function pollPetJob() {
  petProgress.total = petJobKind.value === "expressions" || (petJobKind.value === "candidates" && !pendingExpressionState.value) ? 4 : 1;
  if (petJobTimer) clearTimeout(petJobTimer);
  const epoch = ++petPollingEpoch;
  const jobId = petJobId.value;
  const startedAt = Date.now();
  let failures = 0;
  const stopWaiting = async (message: string) => {
    if (epoch !== petPollingEpoch || petJobId.value !== jobId) return;
    petJobTimer = null;
    petJobStatus.value = "failed";
    petJobError.value = message;
    try {
      await loadPets();
      const refreshed = pets.value.find(p => p.id === expressionPet.value?.id);
      if (refreshed) expressionPet.value = refreshed;
    } catch { /* Keep the actionable error when the connection is unavailable. */ }
  };
  const poll = async () => {
    if (epoch !== petPollingEpoch || petJobId.value !== jobId || petJobStatus.value !== "pending") return;
    if (Date.now() - startedAt > 30 * 60 * 1000) {
      await stopWaiting("等待已超时，请刷新宠物列表检查结果；任务可能仍在执行，请勿直接重复生成。");
      return;
    }
    try {
      const { data } = await petFetch(PET_API + "/jobs/" + jobId);
      if (epoch !== petPollingEpoch || petJobId.value !== jobId || petJobStatus.value !== "pending") return;
      failures = 0;
      const job = data.job;
      if (!job || data.success === false) {
        await stopWaiting(data.message || "任务已失效，请刷新宠物列表检查结果，避免重复生成。");
        return;
      }
      if (job.progress) {
        const previousCompleted = petProgress.completed;
        Object.assign(petProgress, job.progress);
        petElapsedBase = Math.max(0, job.progress.elapsedSeconds || 0);
        petElapsedSyncedAt = Date.now();
        petElapsedSeconds.value = petElapsedBase;
        if (job.status === "pending" && petJobKind.value === "candidates" && petProgress.completed > previousCompleted) {
          await loadPets();
          const updated = pets.value.find(p => p.id === expressionPet.value?.id);
          if (updated) expressionPet.value = updated;
        }
      }
      if (job.status === "done") {
        petJobTimer = null;
        petJobStatus.value = "done";
        await loadPets();
        if (job.petId) {
          const completedPet = pets.value.find(p => p.id === job.petId);
          if ((petJobKind.value === "master" || petJobKind.value === "master-regenerate") && completedPet) {
            revokePetPhotoPreview();
            openBackgroundReview(completedPet, false);
            petJobStatus.value = "done";
            const regenerated = petJobKind.value === "master-regenerate";
            petJobKind.value = regenerated ? "master-regenerate" : "master";
            Toast.success(regenerated
              ? "新母版生成完成，旧表情已清空，请检查背景"
              : "待机母版生成完成，请检查透明背景");
            return;
          }
          if (completedPet) expressionPet.value = completedPet;
          if (petJobKind.value === "candidates") {
            candidateReviewState.value = null;
            petModalPhase.value = hasPetExpressions(completedPet || null) ? "complete" : "expressions";
            Toast.success("新图已保存，请逐张检查背景并确认；当前图片未替换");
            return;
          }
          if (petJobKind.value === "expressions") {
            if (completedPet && hasPetExpressions(completedPet)) {
              endEditorPointer();
              petModalPhase.value = "complete";
            }
            form.widgetPetId = job.petId;
            form.widgetPetPreset = "";
            form.widgetTriggerType = "pet";
          }
        }
        Toast.success(petJobKind.value === "expressions"
          ? "四种表情生成完成"
          : (petJobKind.value === "cleanup" ? "背景残留已清理" : "宠物生成完成"));
      } else if (job.status === "failed") {
        petJobTimer = null;
        petJobStatus.value = "failed";
        petJobError.value = job.error || "生成失败";
        if (petJobKind.value === "candidates") {
          await loadPets();
          const refreshed = pets.value.find(p => p.id === expressionPet.value?.id);
          if (refreshed) expressionPet.value = refreshed;
        }
      } else {
        petJobTimer = setTimeout(poll, 2000);
      }
    } catch (error: any) {
      if (epoch !== petPollingEpoch || petJobId.value !== jobId || petJobStatus.value !== "pending") return;
      if (++failures >= 5) {
        await stopWaiting("无法获取任务状态：" + (error.message || "连接异常") + "。请恢复连接后刷新宠物列表，避免重复生成。");
      } else petJobTimer = setTimeout(poll, 2000);
    }
  };
  petJobTimer = setTimeout(poll, 600);
}

function hasPetExpressions(pet: PetRecord | null): boolean {
  if (!pet) return false;
  return ["blink", "happy", "sad", "thinking"].every(state => Boolean(pet.images?.[state]));
}

function isBackgroundApproved(pet: PetRecord | null): boolean {
  if (!pet) return false;
  // 旧记录没有该字段，继续按已审核处理，避免升级后阻断现有宠物。
  return !pet.backgroundStatus || pet.backgroundStatus === "approved";
}

function petEditActionLabel(pet: PetRecord): string {
  if (!isBackgroundApproved(pet)) return "检查背景";
  return hasPetExpressions(pet) ? "查看表情" : "生成表情";
}

function openPetEditor(pet: PetRecord) {
  if (isBackgroundApproved(pet)) {
    openExpressionEditor(pet);
    if (hasPetExpressions(pet)) petModalPhase.value = "complete";
  } else openBackgroundReview(pet);
}

function preparePetEditor(pet: PetRecord, resetStatus: boolean) {
  expressionPet.value = pet;
  petExpressionMode.value = Object.values(pet.expressionCandidates || {})[0]?.mode || pet.expressionMode || "face";
  petName.value = pet.name;
  petStyle.value = pet.style || "soft-3d";
  petCustomPrompt.value = pet.customPrompt || "";
  petExpressionPrompt.value = pet.expressionPrompt || "";
  showPromptPreview.value = false;
  promptPreview.master = "";
  promptPreview.expressions = {};
  cleanupMode.value = false;
  clearCleanupStrokes();
  backgroundPreviewMode.value = "checker";
  if (resetStatus) {
    petJobStatus.value = "";
    petJobError.value = "";
  }
  petModalOpen.value = true;
  void checkPetModel();
}

function openBackgroundReview(pet: PetRecord | null, resetStatus = true) {
  if (!pet) return;
  endEditorPointer();
  candidateReviewState.value = null;
  petModalPhase.value = "background";
  preparePetEditor(pet, resetStatus);
  cleanupMode.value = true;
  cleanupTool.value = "color";
  petJobKind.value = "cleanup";
}

function openExpressionEditor(pet: PetRecord, resetStatus = true) {
  candidateReviewState.value = null;
  petModalPhase.value = "expressions";
  preparePetEditor(pet, resetStatus);
  const saved = pet.expressionRegion;
  expressionRegion.centerX = saved?.centerX ?? 0.5;
  expressionRegion.centerY = saved?.centerY ?? 0.35;
  expressionRegion.width = saved?.width ?? 0.48;
  expressionRegion.height = saved?.height ?? 0.38;
  expressionRegion.feather = saved?.feather ?? 0.12;
  petJobKind.value = "expressions";
}

function updateExpressionRegionFromPointer(event: PointerEvent) {
  const editor = expressionEditorRef.value;
  if (!editor) return;
  const rect = editor.getBoundingClientRect();
  expressionRegion.centerX = Math.max(0.05, Math.min(0.95, (event.clientX - rect.left) / rect.width));
  expressionRegion.centerY = Math.max(0.05, Math.min(0.95, (event.clientY - rect.top) / rect.height));
}

function beginEditorPointer(event: PointerEvent) {
  if (edgePreviewBusy.value || edgePreviewUrl.value || backgroundPanMode.value || backgroundSpaceDown) return;
  if (candidateReviewBusy.value || petJobStatus.value === "pending") return;
  if (petModalPhase.value === "background" && cleanupMode.value) {
    if (cleanupTool.value === "color") beginColorSelection(event);
    else beginCleanupDraw(event);
  } else if (petModalPhase.value === "expressions") {
    beginExpressionRegionDrag(event);
  }
}

function beginExpressionRegionDrag(event: PointerEvent) {
  event.preventDefault();
  updateExpressionRegionFromPointer(event);
  window.addEventListener("pointermove", updateExpressionRegionFromPointer);
  window.addEventListener("pointerup", endExpressionRegionDrag, { once: true });
}

function endExpressionRegionDrag() {
  window.removeEventListener("pointermove", updateExpressionRegionFromPointer);
}

function loadColorImage(event: Event) {
  if (event.target !== expressionEditorRef.value?.querySelector("img")) return;
  clearCleanupStrokes();
  colorImageReady.value = false;
  colorImageData = null;
  colorSelectionError.value = "";
  const image = event.target as HTMLImageElement;
  colorImageAspect.value = `${image.naturalWidth} / ${image.naturalHeight}`;
  try {
    if (image.naturalWidth * image.naturalHeight > 16_777_216) throw new Error("图片过大");
    const canvas = document.createElement("canvas");
    canvas.width = image.naturalWidth; canvas.height = image.naturalHeight;
    const context = canvas.getContext("2d", {willReadFrequently: true});
    if (!context || !canvas.width || !canvas.height) throw new Error("图片尚未加载");
    context.drawImage(image, 0, 0);
    colorImageData = context.getImageData(0, 0, canvas.width, canvas.height);
    colorImageReady.value = true;
    if (colorOverlayRef.value) { colorOverlayRef.value.width = canvas.width; colorOverlayRef.value.height = canvas.height; }
  } catch {
    colorSelectionError.value = "这张图片暂时无法取色，可继续使用橡皮擦；建议使用本地存储的宠物图片。";
    cleanupTool.value = "brush";
  }
}

watch(backgroundMasterUrl, () => {
  cancelEdgePreview();
  endEditorPointer(); clearCleanupStrokes(); colorImageReady.value = false; colorImageData = null;
});
watch(colorTolerance, () => updateColorPreview());

function updateColorPreview() {
  if (!colorImageData) return;
  const {data, width, height} = colorImageData;
  colorMask = new Uint8Array(width * height);
  for (const selection of colorSelections.value) {
    const selected = selectSimilarPixels(data, width, height, selection, colorTolerance.value);
    for (let i = 0; i < selected.length; i++) if (selected[i]) colorMask[i] = 1;
  }
  const canvas = colorOverlayRef.value;
  const context = canvas?.getContext("2d");
  if (!context) return;
  const overlay = context.createImageData(width, height);
  let count = 0;
  for (let i = 0; i < colorMask.length; i++) if (colorMask[i]) {
    const p = i * 4;
    overlay.data[p] = 225; overlay.data[p + 1] = 29; overlay.data[p + 2] = 72; overlay.data[p + 3] = 125;
    count++;
  }
  colorSelectedCount.value = count;
  context.putImageData(overlay, 0, 0);
}

function colorCleanupPayload() {
  if (!colorImageData || !colorSelectedCount.value) return undefined;
  const runs = selectionRuns(colorMask);
  if (runs.length > 100_000) throw new Error("选区过于复杂，请缩小框选范围");
  return {width: colorImageData.width, height: colorImageData.height, runs};
}

function colorPoint(event: PointerEvent) {
  const rect = expressionEditorRef.value!.getBoundingClientRect();
  return {x: Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width)),
    y: Math.max(0, Math.min(1, (event.clientY - rect.top) / rect.height))};
}

function beginColorSelection(event: PointerEvent) {
  if (!colorImageReady.value || !expressionEditorRef.value || colorSelections.value.length >= 20) return;
  event.preventDefault();
  colorPointerStart = {...colorPoint(event), clientX: event.clientX, clientY: event.clientY};
  window.addEventListener("pointermove", moveColorSelection);
  window.addEventListener("pointerup", finishColorSelection, {once: true});
  window.addEventListener("pointercancel", endColorSelection, {once: true});
}

function moveColorSelection(event: PointerEvent) {
  if (!colorPointerStart || !expressionEditorRef.value) return;
  const end = colorPoint(event), start = colorPointerStart;
  colorDragBox.value = {left: `${Math.min(start.x, end.x) * 100}%`, top: `${Math.min(start.y, end.y) * 100}%`,
    width: `${Math.abs(end.x - start.x) * 100}%`, height: `${Math.abs(end.y - start.y) * 100}%`};
}

function finishColorSelection(event: PointerEvent) {
  const start = colorPointerStart;
  if (start && expressionEditorRef.value) {
    const end = colorPoint(event);
    const selection: ColorSelection = {x: start.x, y: start.y};
    if (Math.hypot(event.clientX - start.clientX, event.clientY - start.clientY) > 5) {
      selection.bounds = [Math.min(start.x, end.x), Math.min(start.y, end.y), Math.max(start.x, end.x), Math.max(start.y, end.y)];
    }
    colorSelections.value.push(selection); cleanupHistory.push("color"); updateColorPreview();
    if (!colorSelectedCount.value) colorSelectionError.value = "未选到可见像素，请从白斑或阴影内部点击或开始拖动。";
    else colorSelectionError.value = "";
  }
  endColorSelection();
}

function endColorSelection() {
  colorPointerStart = null; colorDragBox.value = null;
  window.removeEventListener("pointermove", moveColorSelection);
  window.removeEventListener("pointerup", finishColorSelection);
  window.removeEventListener("pointercancel", endColorSelection);
}

function updateCleanupCursor(event: PointerEvent) {
  if (backgroundPanMode.value || backgroundSpaceDown || backgroundPanStart) { cleanupCursor.value = null; return; }
  const editor = expressionEditorRef.value;
  if (!editor || event.pointerType === "touch") { cleanupCursor.value = null; return; }
  const rect = editor.getBoundingClientRect();
  const x = (event.clientX - rect.left) / rect.width;
  const y = (event.clientY - rect.top) / rect.height;
  cleanupCursor.value = x >= 0 && x <= 1 && y >= 0 && y <= 1 ? { x, y } : null;
}

watch([petModalPhase, cleanupTool, backgroundMasterUrl], () => { cleanupCursor.value = null; });

function cleanupStrokeStyle(stroke: EraseStroke) {
  const [width, height = 1] = colorImageAspect.value.split("/").map(Number);
  const aspect = width > 0 && height > 0 ? width / height : 1;
  return {
    left: `${stroke.x * 100}%`,
    top: `${stroke.y * 100}%`,
    width: `${stroke.radius * 200 * Math.min(1, 1 / aspect)}%`,
    height: `${stroke.radius * 200 * Math.min(1, aspect)}%`,
  };
}

function toggleCleanupMode() {
  endEditorPointer();
  cleanupTool.value = "brush";
  cleanupMode.value = true;
}

function clearCleanupStrokes() {
  cleanupStrokes.value = [];
  colorSelections.value = [];
  cleanupHistory.length = 0;
  colorMask = new Uint8Array(0);
  colorSelectedCount.value = 0;
  const canvas = colorOverlayRef.value;
  canvas?.getContext("2d")?.clearRect(0, 0, canvas.width, canvas.height);
}

function undoCleanupStroke() {
  const tool = cleanupHistory.pop();
  if (tool === "color") { colorSelections.value.pop(); updateColorPreview(); }
  else cleanupStrokes.value = cleanupStrokes.value.slice(0, -1);
}

function addCleanupStroke(event: PointerEvent) {
  const editor = expressionEditorRef.value;
  if (!editor || cleanupStrokes.value.length >= 300) return;
  const rect = editor.getBoundingClientRect();
  const stroke: EraseStroke = {
    x: Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width)),
    y: Math.max(0, Math.min(1, (event.clientY - rect.top) / rect.height)),
    radius: cleanupBrushRadius.value,
  };
  const previous = cleanupStrokes.value[cleanupStrokes.value.length - 1];
  if (previous && Math.hypot(stroke.x - previous.x, stroke.y - previous.y)
      < cleanupBrushRadius.value * 0.28) return;
  cleanupStrokes.value.push(stroke);
  cleanupHistory.push("brush");
}

function beginCleanupDraw(event: PointerEvent) {
  updateCleanupCursor(event);
  event.preventDefault();
  cleanupDrawing = true;
  addCleanupStroke(event);
  window.addEventListener("pointermove", continueCleanupDraw);
  window.addEventListener("pointerup", endCleanupDraw, { once: true });
}

function continueCleanupDraw(event: PointerEvent) {
  updateCleanupCursor(event);
  if (cleanupDrawing) addCleanupStroke(event);
}

function endCleanupDraw() {
  cleanupDrawing = false;
  window.removeEventListener("pointermove", continueCleanupDraw);
}

function endEditorPointer() {
  endBackgroundPan();
  endExpressionRegionDrag();
  endCleanupDraw();
  endColorSelection();
}

async function startBackgroundCleanup() {
  if (candidateReviewState.value) { await candidateOperation("cleanup"); return; }
  const pet = expressionPet.value;
  if (!pet || !cleanupHasMarks.value) return;
  petJobStatus.value = "pending";
  petJobError.value = "";
  petJobKind.value = "cleanup";
  try {
    const { data } = await petFetch(PET_API + "/" + pet.id + "/background/cleanup", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ strokes: cleanupStrokes.value, mask: colorCleanupPayload(), masterUrl: pet.images.idle, masterRevision: pet.cleanupRevision || 0 }),
    });
    if (!data.success) {
      petJobStatus.value = "failed";
      petJobError.value = data.message || "提交失败";
      return;
    }
    petJobId.value = data.jobId;
    pollPetJob();
    clearCleanupStrokes();
    cleanupMode.value = false;
  } catch (e: any) {
    petJobStatus.value = "failed";
    petJobError.value = e.message || "提交失败";
  }
}

async function approveBackground() {
  if (candidateReviewState.value) { await candidateOperation("approve"); return; }
  const pet = expressionPet.value;
  if (!pet || cleanupHasMarks.value) return;
  try {
    const { data } = await petFetch(PET_API + "/" + pet.id + "/background/approve", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ masterUrl: pet.images.idle, masterRevision: pet.cleanupRevision || 0 }),
    });
    if (!data.success) throw new Error(data.message || "确认失败");
    await loadPets();
    const approved = pets.value.find(item => item.id === pet.id) || data.pet;
    Toast.success("母版背景已确认，可以设置表情区域");
    openExpressionEditor(approved, false);
  } catch (e: any) {
    Toast.error("确认母版背景失败：" + (e.message || "未知错误"));
  }
}

async function resetBackground() {
  if (candidateReviewState.value) { await candidateOperation("reset"); return; }
  const pet = expressionPet.value;
  if (!pet) return;
  try {
    const { data } = await petFetch(PET_API + "/" + pet.id + "/background/reset", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ masterUrl: pet.images.idle, masterRevision: pet.cleanupRevision || 0 }),
    });
    if (!data.success) throw new Error(data.message || "恢复失败");
    clearCleanupStrokes();
    await loadPets();
    const resetPet = pets.value.find(item => item.id === pet.id) || data.pet;
    expressionPet.value = resetPet;
    Toast.success("已恢复原始母版，请重新检查背景");
  } catch (e: any) {
    Toast.error("恢复原始母版失败：" + (e.message || "未知错误"));
  }
}

async function openCandidateReview(state: string) {
  let pet = expressionPet.value;
  if (!pet || petJobStatus.value === "pending" || candidateReviewBusy.value) return;
  if (!pet.expressionCandidates?.[state]) {
    if (!pet.images[state]) return;
    candidateReviewBusy.value = true;
    try {
      const {data} = await petFetch(`${PET_API}/${pet.id}/expressions/${state}/edit`, {
        method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({candidateUrl:pet.images[state]}),
      });
      if (!data.success) throw new Error(data.message || '无法打开编辑副本');
      if (!petModalOpen.value || expressionPet.value?.id !== pet.id) return;
      pet = data.pet; pets.value = pets.value.map(item => item.id === pet!.id ? pet! : item);
    } catch (error: any) { Toast.error(error.message || '无法打开编辑副本'); return; }
    finally { candidateReviewBusy.value = false; }
  }
  openBackgroundReview(pet);
  candidateReviewState.value = state;
}

async function discardExistingEdit(pet: PetRecord, state: string) {
  const candidate = pet.expressionCandidates?.[state];
  if (!candidate?.editingExisting) return pet;
  const {data} = await petFetch(`${PET_API}/${pet.id}/expressions/${state}/cancel-edit`, {
    method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({candidateUrl:candidate.imageUrl}),
  });
  if (!data.success) throw new Error(data.message || '取消编辑失败');
  pets.value = pets.value.map(item => item.id === pet.id ? data.pet : item);
  return data.pet as PetRecord;
}
async function returnFromBackgroundReview() {
  const pet = expressionPet.value, state = candidateReviewState.value;
  if (!pet || candidateReviewBusy.value || edgePreviewBusy.value) return;
  candidateReviewBusy.value = true;
  try {
    const restored = state ? await discardExistingEdit(pet, state) : pet;
    openPetEditor(restored);
  } catch (error:any) { Toast.error(error.message); }
  finally { candidateReviewBusy.value = false; }
}

async function candidateOperation(action: "cleanup" | "approve" | "reset") {
  const pet = expressionPet.value;
  const state = candidateReviewState.value;
  const candidate = state && pet?.expressionCandidates?.[state];
  if (!pet || !state || !candidate || candidateReviewBusy.value || petJobStatus.value === "pending") return;
  if (action === "approve" && cleanupHasMarks.value) return;
  candidateReviewBusy.value = true;
  try {
    const { data } = await petFetch(`${PET_API}/${pet.id}/expressions/${state}/${action}`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ candidateUrl: candidate.imageUrl, strokes: cleanupStrokes.value, ...(action === "cleanup" ? {mask: colorCleanupPayload()} : {}) }),
    });
    if (!data.success) throw new Error(data.message || "处理失败");
    await loadPets();
    if (expressionPet.value?.id !== pet.id || candidateReviewState.value !== state) return;
    expressionPet.value = data.pet;
    clearCleanupStrokes();
    if (action === "approve") {
      candidateReviewState.value = null;
      openPetEditor(data.pet);
      Toast.success("该状态已确认并替换，可继续检查其他新图");
    } else Toast.success(action === "cleanup" ? "该图已清理，请继续检查背景" : "已恢复本次生成的原始候选图");
  } catch (error: any) { Toast.error(error.message || "候选图处理失败"); }
  finally { candidateReviewBusy.value = false; }
}

function requestSingleExpression(state: string) {
  if (!petModelReady.value || petJobStatus.value === "pending" || candidateReviewBusy.value || !isBackgroundApproved(expressionPet.value)) return;
  pendingExpressionState.value = state;
  showExpressionRegenerateDialog.value = true;
}

async function startCandidateGenerate() {
  const pet = expressionPet.value;
  if (!pet || !petModelReady.value || petJobStatus.value === "pending" || !isBackgroundApproved(pet)) return;
  petJobStatus.value = "pending";
  petJobError.value = "";
  petJobKind.value = "candidates";
  try {
    const { data } = await petFetch(`${PET_API}/${pet.id}/expressions/generate`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ mode: petExpressionMode.value, state: pendingExpressionState.value,
        customPrompt: petExpressionPrompt.value, ...expressionRegion }),
    });
    if (!data.success) throw new Error(data.message || "提交失败");
    petJobId.value = data.jobId;
    pollPetJob();
  } catch (error: any) { petJobStatus.value = "failed"; petJobError.value = error.message || "提交失败"; }
}

function requestExpressionGenerate() {
  if (!petModelReady.value || !expressionPet.value || !isBackgroundApproved(expressionPet.value) || petJobStatus.value === "pending") return;
  pendingExpressionState.value = null;
  if (hasPetExpressions(expressionPet.value) || hasCandidates.value || petExpressionMode.value === "motion") showExpressionRegenerateDialog.value = true;
  else void startExpressionGenerate();
}

async function confirmExpressionRegenerate() {
  if (!petModalOpen.value || !showExpressionRegenerateDialog.value) return;
  if (pendingExpressionState.value || petExpressionMode.value === "motion") await startCandidateGenerate();
  else await startExpressionGenerate();
}

async function startExpressionGenerate() {
  const pet = expressionPet.value;
  if (!petModelReady.value || !pet || !isBackgroundApproved(pet) || petJobStatus.value === "pending") return;
  petModalPhase.value = "expressions";
  petJobStatus.value = "pending";
  petJobError.value = "";
  petJobKind.value = "expressions";
  try {
    const { data } = await petFetch(PET_API + "/" + pet.id + "/regenerate", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...expressionRegion, customPrompt: petExpressionPrompt.value }),
    });
    if (!data.success) {
      petJobStatus.value = "failed";
      petJobError.value = data.message || "提交失败";
      return;
    }
    petJobId.value = data.jobId;
    pollPetJob();
  } catch (e: any) {
    petJobStatus.value = "failed";
    petJobError.value = e.message || "提交失败";
  }
}

/** 待删除宠物暂存：弹窗描述里要显示名字，确认后才发请求 */
const pendingDeletePet = ref<PetRecord | null>(null);
const showPetDeleteDialog = ref(false);
const petDeleteDescription = computed(() =>
  pendingDeletePet.value
    ? `确定删除「${pendingDeletePet.value.name}」？删除后想再用需要重新上传照片生成，已生成的图片文件仍保留在附件库。`
    : ""
);

function askDeletePet(pet: PetRecord) {
  pendingDeletePet.value = pet;
  showPetDeleteDialog.value = true;
}

/**
 * onConfirm 返回 Promise：VDialog 会显示 loading 并在 resolve 后自动关窗，
 * 所以函数里不用手动改 showPetDeleteDialog
 */
async function confirmDeletePet() {
  const pet = pendingDeletePet.value;
  if (!pet) return;
  try {
    const { data } = await petFetch(PET_API + "/" + pet.id, { method: "DELETE" });
    if (!data.success) {
      Toast.error(data.message || "删除失败");
      return;
    }
    if (form.widgetPetId === pet.id) form.widgetPetId = "";
    await loadPets();
    Toast.success("已删除");
  } catch (e: any) {
    Toast.error("删除失败：" + e.message);
  } finally {
    pendingDeletePet.value = null;
  }
}

function sendPreviewConfig() {
  if (!iframeRef.value?.contentWindow) return;
  // 注意 JSON 往返：petManifest/shortcuts 取自 Vue 响应式对象（Proxy），
  // postMessage 的结构化克隆不接受 reactive Proxy，会抛 DataCloneError 且静默失败。
  // 转成纯普通对象后整棵载荷树都可克隆。
  const payload = JSON.parse(JSON.stringify({
    color: form.widgetThemeColor,
    theme: form.widgetTheme,
    width: form.widgetWidth,
    height: form.widgetHeight,
    position: form.widgetPosition,
    icon: form.widgetIcon,
    triggerLabel: form.widgetTriggerLabel,
    triggerAlign: form.widgetTriggerAlign === "manual" ? "manual" : "auto",
    triggerOffsetY: form.widgetTriggerOffsetY,
    triggerOffsetX: form.widgetTriggerOffsetX,
    triggerShape: form.widgetTriggerShape,
    triggerSize: form.widgetTriggerSize,
    triggerType: form.widgetTriggerType,
    petSize: form.widgetPetSize,
    petGreeting: form.widgetPetGreeting,
    petPhrases: form.widgetPetPhrases
      .split(/\r?\n/)
      .map((s: string) => s.trim())
      .filter(Boolean)
      .slice(0, 10),
    petManifest: selectedPetManifest.value,
    welcome: previewExtras.value.welcome,
    shortcuts: previewExtras.value.shortcuts,
    allowGuest: previewExtras.value.allowGuest,
    allowVisitorReasoning: previewExtras.value.allowVisitorReasoning,
    reasoningDefaultEnabled: previewExtras.value.reasoningDefaultEnabled,
  }));
  iframeRef.value.contentWindow.postMessage({ type: "ai-preview-config", payload }, "*");
}

// 监听视觉属性变化，实时推送到 iframe 预览
watch(
  () => [
    form.widgetThemeColor,
    form.widgetTheme,
    form.widgetWidth,
    form.widgetHeight,
    form.widgetPosition,
    form.widgetIcon,
    form.widgetTriggerLabel,
    form.widgetTriggerAlign,
    form.widgetTriggerOffsetY,
    form.widgetTriggerOffsetX,
    form.widgetTriggerShape,
    form.widgetTriggerSize,
    form.widgetTriggerType,
    form.widgetPetSize,
    form.widgetPetGreeting,
    form.widgetPetPhrases,
    form.widgetPetAvatarCrops,
    selectedPetManifest,
  ],
  () => { sendPreviewConfig(); },
  { deep: true }
);

function resetFields(keys: string[]) {
  keys.forEach(function(k) { (form as any)[k] = (DEFAULTS as any)[k]; });
  Toast.success("已恢复默认");
}

async function save() {
  if (!widgetThemeColorValid.value) {
    saveOk.value = false;
    saveMsg.value = "主题色格式不正确";
    Toast.error("主题色格式不正确");
    return;
  }
  await saveGroup("chat", form, saving, saveMsg, saveOk);
  if (saveOk.value) {
    Toast.success(saveMsg.value || "保存成功");
  } else {
    Toast.error(saveMsg.value || "保存失败");
  }
}

onMounted(async () => {
  window.addEventListener("keydown", backgroundKeyDown);
  window.addEventListener("keyup", backgroundKeyUp);
  window.addEventListener("blur", backgroundBlur);
  const chatGroup = await Promise.all([loadGroup("chat", form), loadPets()]).then(r => r[0]);
  // 预览需要但本页不编辑的对话字段，从整组配置里取快照
  const g: any = chatGroup || {};
  previewExtras.value = {
    welcome: typeof g.welcomeMessage === "string" ? g.welcomeMessage : previewExtras.value.welcome,
    shortcuts: Array.isArray(g.shortcutItems)
      ? g.shortcutItems
          .filter((item: any) => item.enabled && item.query?.trim())
          .slice(0, 6)
          .map((item: any) => ({ ...item }))
      : previewExtras.value.shortcuts,
    allowGuest: g.allowGuest !== undefined ? g.allowGuest : previewExtras.value.allowGuest,
    allowVisitorReasoning: g.allowVisitorReasoning !== undefined ? g.allowVisitorReasoning : previewExtras.value.allowVisitorReasoning,
    reasoningDefaultEnabled: g.reasoningDefaultEnabled !== undefined ? g.reasoningDefaultEnabled : previewExtras.value.reasoningDefaultEnabled,
  };
  sendPreviewConfig();

  if (previewBodyRef.value) {
    previewRO = new ResizeObserver(entries => {
      previewBodyWidth.value = entries[0].contentRect.width;
    });
    previewRO.observe(previewBodyRef.value);
  }
});

onBeforeUnmount(() => {
  window.removeEventListener("keydown", backgroundKeyDown);
  window.removeEventListener("keyup", backgroundKeyUp);
  window.removeEventListener("blur", backgroundBlur);
  endEditorPointer(); cancelEdgePreview();
  ++petPollingEpoch;
  if (petElapsedTimer) clearInterval(petElapsedTimer);
  petElapsedTimer = null;
  previewRO?.disconnect();
  previewRO = null;
  if (petJobTimer) clearTimeout(petJobTimer);
  petJobTimer = null;
  endExpressionRegionDrag();
  revokePetPhotoPreview();
});
</script>

<style scoped>
/* ===== 宠物选择与生成弹窗 ===== */
.ai-pet-avatar-settings { margin-top: 18px; padding: 16px; border: 1px solid #e2e8f0; border-radius: 12px; }
.ai-pet-avatar-previews { display: flex; align-items: center; gap: 28px; margin: 16px 0; }
.ai-pet-avatar-previews > div { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #64748b; }
.ai-pet-avatar-circle { position: relative; width: 56px; height: 56px; border-radius: 50%; overflow: hidden; background: #eef2ff; flex-shrink: 0; }
.ai-pet-avatar-circle.small { width: 32px; height: 32px; }
.ai-pet-avatar-circle img { position: absolute; max-width: none; object-fit: fill; }
.ai-avatar-range { display: block; width: 100%; margin: 8px 0 12px; accent-color: #4f46e5; }

.ai-pet-progress { padding: 14px 16px; border-radius: 10px; background: #eef2ff; color: #475569; font-size: 13px; line-height: 1.8; }
.ai-pet-progress-title { display: flex; align-items: center; gap: 8px; color: #4338ca; font-weight: 600; }
.ai-pet-progress p { margin: 4px 0 0; }
.ai-pet-progress-spinner { width: 14px; height: 14px; border: 2px solid #c7d2fe; border-top-color: #4f46e5; border-radius: 50%; animation: pet-progress-spin 1s linear infinite; }
@keyframes pet-progress-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .ai-pet-progress-spinner { animation: none; } }

.ai-pet-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.ai-pet-card {
  position: relative;
  width: 96px;
  padding: 8px 8px 6px;
  border: 2px solid #e5e7eb;
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #374151;
  transition: border-color 0.15s ease;
}
.ai-pet-card:hover { border-color: #c7d2fe; }
.ai-pet-card.active {
  border-color: var(--ai-chat-color, #4f46e5);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--ai-chat-color, #4f46e5) 15%, transparent);
}
.ai-pet-card img {
  width: 64px;
  height: 64px;
  object-fit: contain;
}
.ai-pet-generated-card { width: 136px; padding: 10px; cursor: default; gap: 0; }
.ai-pet-card-select { display: flex; flex-direction: column; align-items: center; gap: 8px; width: 100%; padding: 0; border: 0; background: transparent; color: inherit; cursor: pointer; }
.ai-pet-card-select img { width: 80px; height: 80px; }
.ai-pet-card-name { width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-align: center; }
.ai-pet-card-actions { display: flex; align-items: center; gap: 6px; width: 100%; margin-top: 12px; padding-top: 10px; border-top: 1px solid #eef0f4; }
.ai-pet-card-primary, .ai-pet-card-more { height: 30px; border: 1px solid #e5e7eb; border-radius: 6px; background: #fff; cursor: pointer; color: #4f46e5; }
.ai-pet-card-primary { flex: 1; padding: 0 6px; font-size: 12px; white-space: nowrap; }
.ai-pet-card-more { width: 30px; padding: 0; font-size: 22px; line-height: 1; color: #6b7280; }
.ai-pet-card-primary:hover, .ai-pet-card-more:hover { background: #f5f3ff; border-color: #c7d2fe; }
.ai-pet-card-select:focus-visible, .ai-pet-card-primary:focus-visible, .ai-pet-card-more:focus-visible, .ai-pet-card-menu button:focus-visible { outline: 2px solid #6366f1; outline-offset: 2px; }
.ai-pet-card-menu { display: grid; min-width: 112px; padding: 4px; }
.ai-pet-card-menu button { border: 0; border-radius: 4px; background: transparent; padding: 8px 12px; text-align: left; font-size: 13px; color: #374151; cursor: pointer; }
.ai-pet-card-menu button:hover { background: #f3f4f6; }
.ai-pet-card-menu button.danger { color: #dc2626; border-top: 1px solid #f3f4f6; }
.ai-pet-generated-card.ai-pet-add { min-height: 172px; cursor: pointer; }
.ai-background-viewport { width: 100%; max-width: 320px; margin: 12px auto; overflow: hidden; background: #e2e8f0; border-radius: 14px; touch-action: none; }
.ai-background-viewport.pan-mode, .ai-background-viewport.pan-mode .ai-expression-editor { cursor: grab; }
.ai-background-viewport .ai-expression-editor { width: 100%; max-width: none; margin: 0; transform-origin: top left; }
.ai-image-navigation, .ai-edge-buttons { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 12px 0; }
.ai-image-navigation button, .ai-edge-buttons button, .ai-edge-controls select { border: 1px solid #d1d5db; background: #fff; border-radius: 6px; padding: 6px 10px; font-size: 12px; cursor: pointer; }
.ai-image-navigation button[aria-pressed="true"] { background: #eef2ff; color: #4338ca; }
.ai-edge-controls { display: grid; gap: 8px; padding: 12px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; margin: 12px 0; }
.ai-edge-controls label { display: flex; align-items: center; gap: 12px; font-size: 12px; }
.ai-edge-buttons button:disabled { opacity: .5; cursor: not-allowed; }
.ai-expression-result-actions { display: flex; flex-wrap: wrap; justify-content: center; gap: 12px; margin-top: 12px; }
.ai-expression-result-actions button { min-width: 108px; min-height: 36px; padding: 8px 16px; border-radius: 8px; font-size: 13px; line-height: 18px; white-space: nowrap; cursor: pointer; }
.ai-expression-review-button { background: #eef2ff; border: 1px solid #c7d2fe; color: #4338ca; }
.ai-expression-regenerate-button { background: #fff; border: 1px solid #d1d5db; color: #4b5563; }
.ai-expression-review-button:hover:not(:disabled) { background: #e0e7ff; }
.ai-expression-regenerate-button:hover:not(:disabled) { background: #f9fafb; border-color: #9ca3af; }
.ai-expression-result-actions button:focus-visible { outline: 2px solid #6366f1; outline-offset: 2px; }
.ai-expression-result-actions button:disabled { opacity: 0.5; cursor: not-allowed; }
.ai-pet-rename-form { display: grid; gap: 10px; padding: 20px; }
.ai-pet-rename-actions { display: flex; justify-content: flex-end; gap: 10px; padding: 14px 20px; }
.ai-pet-rename-error { margin: 0; color: #dc2626; font-size: 12px; }
.ai-pet-add {
  justify-content: center;
  color: #6b7280;
  border-style: dashed;
  min-height: 108px;
}
.ai-pet-add-icon { font-size: 24px; line-height: 1; }

.ai-pet-modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(17, 24, 39, 0.45);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
}
.ai-pet-modal {
  width: 520px;
  max-width: calc(100vw - 48px);
  max-height: calc(100vh - 48px);
  overflow-y: auto;
  background: #fff;
  border-radius: 14px;
  padding: 20px 22px;
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.2);
}
.ai-pet-modal-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 14px;
}
.ai-pet-modal-body { display: flex; flex-direction: column; gap: 12px; }
.ai-pet-upload {
  border: 2px dashed #d1d5db;
  border-radius: 10px;
  min-height: 140px;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: #9ca3af;
  font-size: 13px;
  cursor: pointer;
  overflow: hidden;
}
.ai-pet-upload:hover { border-color: #a5b4fc; }
.ai-pet-upload img {
  width: 100%;
  max-height: 220px;
  object-fit: cover;
}
.ai-optional {
  color: #94a3b8;
  font-size: 11px;
  font-weight: 400;
}
.ai-prompt-input {
  width: 100%;
  min-height: 64px;
  resize: vertical;
  line-height: 1.55;
}
.ai-prompt-meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 5px;
  color: #94a3b8;
  font-size: 11px;
  line-height: 1.5;
}
.ai-prompt-meta span:first-child { flex: 1; }
.ai-prompt-toggle {
  align-self: flex-start;
  padding: 0;
  border: 0;
  background: transparent;
  color: #4f46e5;
  font-size: 12px;
  cursor: pointer;
}
.ai-prompt-toggle:hover { color: #3730a3; }
.ai-prompt-toggle:disabled { color: #94a3b8; cursor: not-allowed; }
.ai-upload-guidance {
  padding: 9px 11px;
  border-radius: 9px;
  background: #f8fafc;
  color: #64748b;
  font-size: 11px;
  line-height: 1.6;
}
.ai-prompt-preview {
  display: flex;
  flex-direction: column;
  gap: 7px;
  max-height: 260px;
  overflow: auto;
  padding: 10px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 9px;
  background: #f8fafc;
  color: #64748b;
  font-size: 11px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.ai-prompt-preview strong,
.ai-prompt-preview b { color: #334155; }
.ai-pipeline-steps {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
  font-size: 11px;
  color: #94a3b8;
}
.ai-pipeline-steps span {
  padding: 6px 8px;
  border-radius: 999px;
  background: #f1f5f9;
  text-align: center;
  white-space: nowrap;
}
.ai-pipeline-steps span.active {
  color: #4338ca;
  background: #eef2ff;
  font-weight: 600;
}
.ai-expression-help {
  font-size: 12px;
  line-height: 1.6;
  color: #64748b;
}
.ai-expression-editor {
  position: relative;
  width: min(320px, 100%);
  aspect-ratio: 1;
  align-self: center;
  overflow: hidden;
  border: 1px solid #dbe3ef;
  border-radius: 14px;
  background-color: #f8fafc;
  background-image:
    linear-gradient(45deg, #edf0f4 25%, transparent 25%),
    linear-gradient(-45deg, #edf0f4 25%, transparent 25%),
    linear-gradient(45deg, transparent 75%, #edf0f4 75%),
    linear-gradient(-45deg, transparent 75%, #edf0f4 75%);
  background-size: 20px 20px;
  background-position: 0 0, 0 10px, 10px -10px, -10px 0;
  cursor: crosshair;
  touch-action: none;
  user-select: none;
}
.ai-expression-editor img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: contain;
  pointer-events: none;
}
.ai-expression-editor.preview-light {
  background: #f8fafc;
}
.ai-expression-editor.preview-dark {
  background: #172033;
}
.ai-background-preview-tabs {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
}
.ai-background-preview-tabs button {
  padding: 7px 10px;
  border: 1px solid #dbe3ef;
  border-radius: 8px;
  background: #fff;
  color: #64748b;
  font-family: inherit;
  font-size: 11px;
  cursor: pointer;
}
.ai-background-preview-tabs button.active {
  border-color: #6366f1;
  background: #eef2ff;
  color: #4338ca;
  font-weight: 600;
}
.ai-cleanup-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
  color: #64748b;
  font-size: 11px;
  line-height: 1.5;
}
.ai-cleanup-toggle,
.ai-cleanup-apply {
  padding: 7px 11px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  background: #fff;
  color: #334155;
  font-family: inherit;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}
.ai-cleanup-toggle:hover:not(:disabled) { border-color: #94a3b8; }
.ai-cleanup-toggle.active {
  border-color: #ef4444;
  background: #fff1f2;
  color: #be123c;
}
.ai-cleanup-toggle:disabled,
.ai-cleanup-apply:disabled { opacity: 0.5; cursor: not-allowed; }
.ai-expression-editor.cleanup-active { cursor: cell; }
.ai-expression-editor.brush-active { cursor: none; }
.ai-cleanup-cursor { position: absolute; z-index: 4; transform: translate(-50%, -50%); box-sizing: border-box; border: 1px solid #fff; border-radius: 50%; box-shadow: 0 0 0 1px #111827, inset 0 0 0 1px #111827; pointer-events: none; }
.ai-color-overlay { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; z-index: 2; }
.ai-color-drag-box { position: absolute; border: 2px dashed #e11d48; background: rgba(225,29,72,.08); pointer-events: none; z-index: 3; }
.ai-cleanup-mark {
  position: absolute;
  z-index: 2;
  transform: translate(-50%, -50%);
  border: 1px solid rgba(225, 29, 72, 0.76);
  border-radius: 50%;
  background: rgba(251, 113, 133, 0.32);
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.72) inset;
  pointer-events: none;
}
.ai-cleanup-controls {
  display: grid;
  grid-template-columns: minmax(150px, 1fr) auto auto auto auto;
  align-items: center;
  gap: 10px;
  color: #64748b;
  font-size: 11px;
}
.ai-cleanup-controls label {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ai-cleanup-controls strong { color: #334155; font-weight: 600; }
.ai-cleanup-controls input { width: 100%; accent-color: #e11d48; }
.ai-cleanup-apply {
  border-color: #e11d48;
  background: #e11d48;
  color: #fff;
}
.ai-cleanup-apply:hover:not(:disabled) { background: #be123c; }
.ai-background-review-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 11px;
  border: 1px solid #e2e8f0;
  border-radius: 9px;
  background: #f8fafc;
  color: #64748b;
  font-size: 11px;
}
.ai-expression-region {
  position: absolute;
  transform: translate(-50%, -50%);
  border: 2px dashed #6366f1;
  border-radius: 50%;
  box-shadow: 0 0 0 999px rgba(15, 23, 42, 0.38);
  pointer-events: none;
}
.ai-expression-region::after {
  content: "表情区域";
  position: absolute;
  left: 50%;
  bottom: -25px;
  transform: translateX(-50%);
  padding: 2px 7px;
  border-radius: 999px;
  color: #fff;
  background: #4f46e5;
  font-size: 10px;
  white-space: nowrap;
}
.ai-expression-controls {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}
.ai-expression-controls label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  color: #64748b;
  font-size: 11px;
}
.ai-expression-controls strong { color: #334155; font-weight: 600; }
.ai-expression-controls input { width: 100%; accent-color: #4f46e5; }

/* 布局与「对话行为」页一致：双列封顶 grid + 预览卡 sticky，页面自然滚动 */
.widget-page {
  container-type: inline-size;
  min-height: 100%;
  background: #f5f7fb;
}
.widget-page .ai-content {
  display: grid;
  grid-template-columns: minmax(480px, 600px) minmax(380px, 460px);
  align-items: start;
  gap: 20px;
  padding: 20px 24px 44px;
}
.chat-config {
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.chat-config-scroll {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.chat-config-scroll :deep(.ai-section-block) { margin-bottom: 0; }

.chat-preview {
  min-width: 0;
  position: sticky;
  top: 16px;
}

/* 预览卡片外壳，与 SectionCard 同款语言 */
.widget-preview-panel {
  display: flex;
  flex-direction: column;
  background: var(--ai-color-bg-card);
  border: 1px solid #e5e7eb;
  border-radius: var(--ai-radius-xl);
  overflow: hidden;
}
.widget-preview-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 18px;
  border-bottom: 1px solid #e5e7eb;
  background: #ffffff;
  flex-shrink: 0;
}
.widget-preview-icon {
  width: 28px;
  height: 28px;
  border-radius: var(--ai-radius-md);
  background: #f8fafc;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #64748b;
  flex-shrink: 0;
}
.widget-preview-icon :deep(svg) { width: 16px; height: 16px; }
.widget-preview-info { flex: 1; min-width: 0; }
.widget-preview-title {
  font-size: 15px;
  font-weight: 600;
  color: #111827;
  line-height: 1.4;
}
.widget-preview-desc {
  margin-top: 3px;
  font-size: 12px;
  color: #64748b;
}
.widget-preview-body {
  padding: 18px;
  display: flex;
  justify-content: center;
}
.widget-preview-stage {
  position: relative;
  overflow: hidden;
  border-radius: 12px;
  background: #f8fafc;
  border: 1px dashed #e2e8f0;
}
.ai-preview-iframe {
  display: block;
  border: none;
  background: #fff;
  transform-origin: top left;
}

/* 悬浮按钮图标网格选择器 — 所见即所得，SVG 与访客端同源 */
.ai-icon-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 8px;
  margin-top: 4px;
}
.ai-icon-grid-item {
  display: flex;
  align-items: center;
  justify-content: center;
  aspect-ratio: 1;
  padding: 0;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fff;
  color: #4b5563;
  font-size: 22px;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s, box-shadow 0.15s, transform 0.1s;
}
.ai-icon-grid-item :deep(svg) { width: 1em; height: 1em; }
.ai-icon-grid-item:hover { color: #111827; border-color: #cbd5e1; }
.ai-icon-grid-item:active { transform: scale(0.94); }
.ai-icon-grid-item.active {
  color: var(--ai-chat-color, #4F46E5);
  border-color: var(--ai-chat-color, #4F46E5);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--ai-chat-color, #4F46E5) 18%, transparent);
}
.ai-icon-grid-item.active :deep(svg) { fill: currentColor; }

/* 按钮形状选择器 — 用主题色预览块展示真实 border-radius */
.ai-shape-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  margin-top: 4px;
}
.ai-shape-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 10px 4px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.ai-shape-item:hover { border-color: #cbd5e1; }
.ai-shape-item.active {
  border-color: var(--ai-chat-color, #4F46E5);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--ai-chat-color, #4F46E5) 18%, transparent);
}
.ai-shape-preview {
  width: 28px;
  height: 28px;
  background: var(--ai-chat-color, #4F46E5);
}
.ai-shape-label {
  font-size: 12px;
  color: #4b5563;
  white-space: nowrap;
}
.ai-shape-item.active .ai-shape-label { color: var(--ai-chat-color, #4F46E5); font-weight: 600; }

/* 触发器样式选择器 — 真实素材所见即所得（当前图标 SVG / 当前宠物图） */
.trigger-type-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 4px;
}
.trigger-type-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  font-family: inherit;
  text-align: left;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.trigger-type-card:hover { border-color: #cbd5e1; }
.trigger-type-card.active {
  border-color: var(--ai-chat-color, #4F46E5);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--ai-chat-color, #4F46E5) 18%, transparent);
}
.trigger-type-preview {
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.trigger-type-icon {
  display: flex;
  font-size: 21px;
  color: var(--ai-chat-color, #4F46E5);
}
.trigger-type-icon :deep(svg) { width: 1em; height: 1em; }
.trigger-type-preview img {
  width: 34px;
  height: 34px;
  object-fit: contain;
}
.trigger-type-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.trigger-type-name { font-size: 13px; font-weight: 600; color: #1f2937; line-height: 1.4; }
.trigger-type-card.active .trigger-type-name { color: var(--ai-chat-color, #4F46E5); }
.trigger-type-desc { font-size: 11px; color: #94a3b8; line-height: 1.4; }

/* 表单工具样式 */
.ai-form-grid-2 { display: grid; grid-template-columns: repeat(2, 1fr); gap: 18px; }
.ai-form-grid-2 .ai-input[type="number"] { max-width: 220px; }
.ai-input[type="number"] { border: 1px solid #94a3b8 !important; background: #fff !important; -webkit-appearance: none; -moz-appearance: textfield; appearance: none; }
.ai-helper-text.error { color: #dc2626; }

/* 本页开关项采用轻量列表行，与「对话行为」页一致 */
.chat-config-scroll :deep(.ai-option-grid) { gap: 10px; }
.chat-config-scroll :deep(.ai-option-card) {
  padding: 13px 16px;
  border-radius: 10px;
  box-shadow: none;
}
.chat-config-scroll :deep(.ai-option-card:hover),
.chat-config-scroll :deep(.ai-option-card.active) { box-shadow: none; }

/* 根据插件内容区而非浏览器视口切换布局 */
@container (max-width: 959px) {
  .widget-page .ai-content {
    grid-template-columns: minmax(0, 1fr);
    padding: 16px 16px 36px;
  }

  /* 窄屏时预览置于配置之前，所见即所得的反馈更直接 */
  .chat-preview {
    grid-row: 1;
    position: static;
  }
  .chat-config {
    grid-row: 2;
  }
}

@container (max-width: 639px) {
  .widget-page .ai-content {
    padding: 12px 10px 28px;
  }

  .chat-config-scroll {
    gap: 16px;
  }

  .ai-form-grid-2 {
    grid-template-columns: 1fr;
    gap: 14px;
  }

  .ai-icon-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .ai-shape-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .ai-expression-controls {
    grid-template-columns: 1fr;
  }

  .ai-cleanup-controls {
    grid-template-columns: 1fr auto;
  }
}

@container (max-width: 419px) {
  .ai-icon-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
</style>

<style scoped>
.ai-expression-results {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin: 20px 0;
}
.ai-expression-results figure { margin: 0; }
.ai-expression-result-image {
  display: flex;
  justify-content: center;
  padding: 16px;
  border-radius: 12px;
  background-color: #e2e8f0;
  background-image: linear-gradient(45deg, #cbd5e1 25%, transparent 25%), linear-gradient(-45deg, #cbd5e1 25%, transparent 25%), linear-gradient(45deg, transparent 75%, #cbd5e1 75%), linear-gradient(-45deg, transparent 75%, #cbd5e1 75%);
  background-size: 20px 20px;
  background-position: 0 0, 0 10px, 10px -10px, -10px 0;
}
.ai-expression-result-image img { width: 128px; height: 128px; max-width: 100%; object-fit: contain; }
.ai-expression-results figcaption { text-align: center; margin-top: 8px; font-weight: 600; }
@media (max-width: 480px) { .ai-expression-result-image img { width: 96px; height: 96px; } }
</style>

<style scoped>
.ai-pet-model-notice { margin-bottom: 16px; padding: 12px 16px; border-radius: 10px; background: #f1f5f9; color: #475569; font-size: 13px; line-height: 1.6; }
.ai-pet-model-notice strong { color: #334155; }
.ai-pet-model-notice p { margin: 4px 0 0; }
</style>
