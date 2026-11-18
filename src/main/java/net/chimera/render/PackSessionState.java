// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.chimera.shaderpack.PassComputePlan;
import net.chimera.shaderpack.ShadowCullingPlan;
import net.chimera.shaderpack.ShadowPassUse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One pack session's flags, once-only logs and plans that no owner closes. PackSessionLifecycle's
 * cleanUpPackVariant replaces it whole, so a field added here starts each session clear with no reset
 * list to forget.
 *
 * Kept out on purpose: what must outlive the cleanup (packFrameUnsupported, which renderVanilla sets
 * before it cleans up, and the conformance report a failure's report reads after it), the plan and the
 * resource owners, which close in order, and per-frame flags, which each frame resets.
 */
final class PackSessionState {
    // main pass
    /** Set once the session's pipelines are installed; they stay until it ends. */
    boolean packPipelinesLoaded;
    boolean packPostChainActive;
    /** The target images exist; geometry may need them even with no post pass installed. */
    boolean packTargetResourcesReady;
    /** Set when the target graph cannot be configured, or a runtime failure turns the pack off. */
    boolean packPostChainRejected;
    boolean packStorageInitializationLogged;
    /** PACK_TARGET_FRAME_NOT_STARTED is logged once per session. */
    boolean packTargetFrameNotStartedLogged;
    boolean packTranslucentSuspendLogged;
    /** A failed pre-hand depth capture is warned about once per session. */
    boolean preHandDepthFailureLogged;
    /** The loaded pack's pass computes, as its analysis translated them. */
    PassComputePlan passComputes = PassComputePlan.empty();

    // shadow pass
    /** Set once the caster pass failed; the shadow map keeps its terrain for the rest of the session. */
    boolean shadowCastersFailed;
    /** Iris's shadow culling for this pack session (shadow.culling and the shadow distances). */
    ShadowCullingPlan shadowCulling;
    /** Who observes the pack's shadow pass, and so whether it draws (Iris's ShadowRenderer rule). */
    ShadowPassUse shadowUse = ShadowPassUse.ALWAYS;
    boolean shadowSegmentObserved;
    /** A shadow resource fallback is warned about once per session. */
    boolean shadowTransitionFallbackLogged;
    /** The shadow cutout layer's path, merged into opaque or separate, is reported once per session. */
    boolean shadowCutoutDispositionLogged;
    /** Programs whose compare shadow sampler was already logged. */
    final Set<String> loggedShadowSamplerPrograms = new HashSet<>();

    // post chain
    /** The session's post stages in run order, uninstalled and compute-only ones included. */
    final List<PackPostChain.PackPostExecution> packPostExecution = new ArrayList<>();
    /** Stages whose identity boundary was logged, once per session. */
    final Set<String> packPostBoundaryLogged = new HashSet<>();
    /** Stages that threw while recording this session; they run as identity. */
    final PostStageFailures stageFailures = new PostStageFailures();
    PackPostChain.PackPostExecution packFinalExecution;
}
