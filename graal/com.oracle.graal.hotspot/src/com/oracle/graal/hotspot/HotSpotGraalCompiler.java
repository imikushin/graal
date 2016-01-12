/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.hotspot;

import static com.oracle.graal.compiler.common.GraalOptions.OptAssumptions;
import static com.oracle.graal.nodes.graphbuilderconf.IntrinsicContext.CompilationContext.ROOT_COMPILATION;
import static jdk.vm.ci.code.CallingConvention.Type.JavaCallee;
import static jdk.vm.ci.code.CodeUtil.getCallingConvention;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.CallingConvention.Type;
import jdk.vm.ci.code.CompilationRequest;
import jdk.vm.ci.code.CompilationResult;
import jdk.vm.ci.hotspot.HotSpotCodeCacheProvider;
import jdk.vm.ci.hotspot.HotSpotCompilationRequest;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntimeProvider;
import jdk.vm.ci.meta.DefaultProfilingInfo;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ProfilingInfo;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.SpeculationLog;
import jdk.vm.ci.meta.TriState;
import jdk.vm.ci.runtime.JVMCICompiler;

import com.oracle.graal.api.runtime.GraalJVMCICompiler;
import com.oracle.graal.compiler.GraalCompiler;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.DebugConfigScope;
import com.oracle.graal.debug.DebugEnvironment;
import com.oracle.graal.debug.TTY;
import com.oracle.graal.debug.TopLevelDebugConfig;
import com.oracle.graal.debug.internal.DebugScope;
import com.oracle.graal.hotspot.meta.HotSpotProviders;
import com.oracle.graal.hotspot.phases.OnStackReplacementPhase;
import com.oracle.graal.java.GraphBuilderPhase;
import com.oracle.graal.lir.asm.CompilationResultBuilderFactory;
import com.oracle.graal.lir.phases.LIRSuites;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.StructuredGraph.AllowAssumptions;
import com.oracle.graal.nodes.graphbuilderconf.GraphBuilderConfiguration;
import com.oracle.graal.nodes.graphbuilderconf.GraphBuilderConfiguration.DebugInfoMode;
import com.oracle.graal.nodes.graphbuilderconf.GraphBuilderConfiguration.Plugins;
import com.oracle.graal.nodes.graphbuilderconf.IntrinsicContext;
import com.oracle.graal.nodes.spi.Replacements;
import com.oracle.graal.phases.OptimisticOptimizations;
import com.oracle.graal.phases.OptimisticOptimizations.Optimization;
import com.oracle.graal.phases.PhaseSuite;
import com.oracle.graal.phases.tiers.HighTierContext;
import com.oracle.graal.phases.tiers.Suites;

public class HotSpotGraalCompiler implements GraalJVMCICompiler {

    private final HotSpotJVMCIRuntimeProvider jvmciRuntime;
    private final HotSpotGraalRuntimeProvider graalRuntime;

    HotSpotGraalCompiler(HotSpotJVMCIRuntimeProvider jvmciRuntime, HotSpotGraalRuntimeProvider graalRuntime) {
        this.jvmciRuntime = jvmciRuntime;
        this.graalRuntime = graalRuntime;
    }

    @Override
    public HotSpotGraalRuntimeProvider getGraalRuntime() {
        return graalRuntime;
    }

    @Override
    @SuppressWarnings("try")
    public void compileMethod(CompilationRequest request) {
        // Ensure a debug configuration for this thread is initialized
        if (Debug.isEnabled() && DebugScope.getConfig() == null) {
            DebugEnvironment.initialize(TTY.out);
        }

        CompilationTask task = new CompilationTask(jvmciRuntime, this, (HotSpotCompilationRequest) request, true, true);
        try (DebugConfigScope dcs = Debug.setConfig(new TopLevelDebugConfig())) {
            task.runCompilation();
        }
    }

    public void compileTheWorld() throws Throwable {
        HotSpotCodeCacheProvider codeCache = (HotSpotCodeCacheProvider) jvmciRuntime.getHostJVMCIBackend().getCodeCache();
        int iterations = CompileTheWorldOptions.CompileTheWorldIterations.getValue();
        for (int i = 0; i < iterations; i++) {
            codeCache.resetCompilationStatistics();
            TTY.println("CompileTheWorld : iteration " + i);
            CompileTheWorld ctw = new CompileTheWorld(jvmciRuntime, this);
            ctw.compile();
        }
        System.exit(0);
    }

    public CompilationResult compile(ResolvedJavaMethod method, int entryBCI, boolean useProfilingInfo) {
        HotSpotBackend backend = graalRuntime.getHostBackend();
        HotSpotProviders providers = backend.getProviders();
        final boolean isOSR = entryBCI != JVMCICompiler.INVOCATION_ENTRY_BCI;
        StructuredGraph graph = method.isNative() || isOSR ? null : getIntrinsicGraph(method, providers);

        if (graph == null) {
            SpeculationLog speculationLog = method.getSpeculationLog();
            if (speculationLog != null) {
                speculationLog.collectFailedSpeculations();
            }
            graph = new StructuredGraph(method, entryBCI, AllowAssumptions.from(OptAssumptions.getValue()), speculationLog);
        }

        CallingConvention cc = getCallingConvention(providers.getCodeCache(), Type.JavaCallee, graph.method(), false);
        if (isOSR) {
            // for OSR, only a pointer is passed to the method.
            JavaType[] parameterTypes = new JavaType[]{providers.getMetaAccess().lookupJavaType(long.class)};
            CallingConvention tmp = providers.getCodeCache().getRegisterConfig().getCallingConvention(JavaCallee, providers.getMetaAccess().lookupJavaType(void.class), parameterTypes,
                            backend.getTarget(), false);
            cc = new CallingConvention(cc.getStackSize(), cc.getReturn(), tmp.getArgument(0));
        }
        Suites suites = getSuites(providers);
        LIRSuites lirSuites = getLIRSuites(providers);
        ProfilingInfo profilingInfo = useProfilingInfo ? method.getProfilingInfo(!isOSR, isOSR) : DefaultProfilingInfo.get(TriState.FALSE);
        OptimisticOptimizations optimisticOpts = getOptimisticOpts(profilingInfo);
        if (isOSR) {
            // In OSR compiles, we cannot rely on never executed code profiles, because
            // all code after the OSR loop is never executed.
            optimisticOpts.remove(Optimization.RemoveNeverExecutedCode);
        }
        CompilationResult result = new CompilationResult();
        result.setEntryBCI(entryBCI);
        boolean shouldDebugNonSafepoints = providers.getCodeCache().shouldDebugNonSafepoints();
        PhaseSuite<HighTierContext> graphBuilderSuite = configGraphBuilderSuite(providers.getSuites().getDefaultGraphBuilderSuite(), shouldDebugNonSafepoints, isOSR, useProfilingInfo);
        GraalCompiler.compileGraph(graph, cc, method, providers, backend, graphBuilderSuite, optimisticOpts, profilingInfo, suites, lirSuites, result, CompilationResultBuilderFactory.Default);

        if (!isOSR && useProfilingInfo) {
            ProfilingInfo profile = method.getProfilingInfo();
            profile.setCompilerIRSize(StructuredGraph.class, graph.getNodeCount());
        }

        return result;
    }

    /**
     * Gets a graph produced from the intrinsic for a given method that can be compiled and
     * installed for the method.
     *
     * @param method
     * @return an intrinsic graph that can be compiled and installed for {@code method} or null
     */
    protected StructuredGraph getIntrinsicGraph(ResolvedJavaMethod method, HotSpotProviders providers) {
        Replacements replacements = providers.getReplacements();
        ResolvedJavaMethod substMethod = replacements.getSubstitutionMethod(method);
        if (substMethod != null) {
            assert !substMethod.equals(method);
            StructuredGraph graph = new StructuredGraph(substMethod, AllowAssumptions.YES);
            Plugins plugins = new Plugins(providers.getGraphBuilderPlugins());
            GraphBuilderConfiguration config = GraphBuilderConfiguration.getSnippetDefault(plugins);
            IntrinsicContext initialReplacementContext = new IntrinsicContext(method, substMethod, ROOT_COMPILATION);
            new GraphBuilderPhase.Instance(providers.getMetaAccess(), providers.getStampProvider(), providers.getConstantReflection(), config, OptimisticOptimizations.NONE, initialReplacementContext).apply(graph);
            assert !graph.isFrozen();
            return graph;
        }
        return null;
    }

    protected OptimisticOptimizations getOptimisticOpts(ProfilingInfo profilingInfo) {
        return new OptimisticOptimizations(profilingInfo);
    }

    protected Suites getSuites(HotSpotProviders providers) {
        return providers.getSuites().getDefaultSuites();
    }

    protected LIRSuites getLIRSuites(HotSpotProviders providers) {
        return providers.getSuites().getDefaultLIRSuites();
    }

    /**
     * Reconfigures a given graph builder suite (GBS) if one of the given GBS parameter values is
     * not the default.
     *
     * @param suite the graph builder suite
     * @param shouldDebugNonSafepoints specifies if extra debug info should be generated (default is
     *            false)
     * @param isOSR specifies if extra OSR-specific post-processing is required (default is false)
     * @param useProfilingInfo specifies if the graph builder should use profiling info (default is
     *            true)
     * @return a new suite derived from {@code suite} if any of the GBS parameters did not have a
     *         default value otherwise {@code suite}
     */
    protected PhaseSuite<HighTierContext> configGraphBuilderSuite(PhaseSuite<HighTierContext> suite, boolean shouldDebugNonSafepoints, boolean isOSR, boolean useProfilingInfo) {
        if (shouldDebugNonSafepoints || isOSR || !useProfilingInfo) {
            PhaseSuite<HighTierContext> newGbs = suite.copy();

            if (shouldDebugNonSafepoints || !useProfilingInfo) {
                // This complexity below is to ensure exactly one
                // GraphBuilderConfiguration copy is made.
                GraphBuilderPhase graphBuilderPhase = (GraphBuilderPhase) newGbs.findPhase(GraphBuilderPhase.class).previous();
                GraphBuilderConfiguration graphBuilderConfig = graphBuilderPhase.getGraphBuilderConfig();
                if (shouldDebugNonSafepoints) {
                    graphBuilderConfig = graphBuilderConfig.withDebugInfoMode(DebugInfoMode.Simple);
                    if (!useProfilingInfo) {
                        graphBuilderConfig.setUseProfiling(false);
                    }
                } else {
                    assert !useProfilingInfo;
                    graphBuilderConfig = graphBuilderConfig.copy();
                    graphBuilderConfig.setUseProfiling(false);
                }

                GraphBuilderPhase newGraphBuilderPhase = new GraphBuilderPhase(graphBuilderConfig);
                newGbs.findPhase(GraphBuilderPhase.class).set(newGraphBuilderPhase);
            }
            if (isOSR) {
                newGbs.appendPhase(new OnStackReplacementPhase());
            }
            return newGbs;
        }
        return suite;
    }
}