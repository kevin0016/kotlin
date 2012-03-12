/*
 * Copyright 2010-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.lang.resolve;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.descriptors.annotations.AnnotationDescriptor;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.resolve.calls.CallMaker;
import org.jetbrains.jet.lang.resolve.calls.CallResolver;
import org.jetbrains.jet.lang.resolve.calls.OverloadResolutionResults;
import org.jetbrains.jet.lang.resolve.calls.ResolvedValueArgument;
import org.jetbrains.jet.lang.resolve.calls.autocasts.DataFlowInfo;
import org.jetbrains.jet.lang.resolve.constants.CompileTimeConstant;
import org.jetbrains.jet.lang.resolve.scopes.JetScope;
import org.jetbrains.jet.lang.resolve.scopes.receivers.ReceiverDescriptor;
import org.jetbrains.jet.lang.types.ErrorUtils;
import org.jetbrains.jet.lang.types.JetType;
import org.jetbrains.jet.lang.types.expressions.ExpressionTypingServices;

import javax.inject.Inject;
import java.util.*;

import static org.jetbrains.jet.lang.types.TypeUtils.NO_EXPECTED_TYPE;

/**
 * @author abreslav
 */
public class AnnotationResolver {

    private ExpressionTypingServices expressionTypingServices;
    private CallResolver callResolver;
    @NotNull
    private TopDownAnalysisContext context;

    @Inject
    public void setExpressionTypingServices(ExpressionTypingServices expressionTypingServices) {
        this.expressionTypingServices = expressionTypingServices;
    }

    @Inject
    public void setCallResolver(CallResolver callResolver) {
        this.callResolver = callResolver;
    }

    @com.google.inject.Inject
    public void setContext(@NotNull TopDownAnalysisContext context) {
        this.context = context;
    }

    public void process() {
        final BindingTrace trace = context.getTrace();

        HashSet<Class<?>> classLevelSkips = new HashSet<Class<?>>();
        classLevelSkips.add(JetDeclarationWithBody.class);
        classLevelSkips.add(JetProperty.class);

        for (Map.Entry<JetClass, MutableClassDescriptor> entry : this.context.getClasses().entrySet()) {
            JetClass declaration = entry.getKey();
            MutableClassDescriptor descriptor = entry.getValue();
            resolveAnnotationExpressions(trace, declaration, descriptor.getScopeForInitializers(), classLevelSkips);
        }

        for (Map.Entry<JetObjectDeclaration, MutableClassDescriptor> entry : this.context.getObjects().entrySet()) {
            JetObjectDeclaration declaration = entry.getKey();
            MutableClassDescriptor descriptor = entry.getValue();
            resolveAnnotationExpressions(trace, declaration, descriptor.getScopeForInitializers(), classLevelSkips);
        }

        for (JetProperty declaration : this.context.getProperties().keySet()) {
            JetScope declaringScope = this.context.getDeclaringScopes().get(declaration);
            assert declaringScope != null;
            JetExpression expression = declaration.getInitializer();
            if(expression!=null) {
                resolveAnnotationExpressions(trace, expression, declaringScope, new HashSet<Class<?>>());
            }
        }

        for (Map.Entry<JetNamedFunction, SimpleFunctionDescriptor> entry : this.context.getFunctions().entrySet()) {
            JetNamedFunction declaration = entry.getKey();
            SimpleFunctionDescriptor descriptor = entry.getValue();
            JetScope declaringScope = this.context.getDeclaringScopes().get(declaration);
            assert declaringScope != null;

            for (JetParameter param : declaration.getValueParameters()) {
                JetExpression expression = param.getDefaultValue();
                if(expression!=null) {
                    resolveAnnotationExpressions(trace, expression, declaringScope, new HashSet<Class<?>>());
                }
            }

            JetExpression expression = declaration.getBodyExpression();
            if(expression!=null) {
                final JetScope innerScope = FunctionDescriptorUtil.getFunctionInnerScope(declaringScope, descriptor, trace);
                resolveAnnotationExpressions(trace, expression, innerScope, new HashSet<Class<?>>());
            }
        }
    }

    private void resolveAnnotationExpressions(@NotNull final BindingTrace trace, @NotNull JetExpression expression, @NotNull final JetScope scope, @NotNull  final HashSet<Class<?>> skip) {
        expression.accept(new JetVisitorVoid() {
            @Override
            public void visitElement(PsiElement element) {
                for (Class<?> aClass : skip) {
                    if(aClass.isAssignableFrom(element.getClass()) ) {
                        return;
                    }
                }
                element.acceptChildren(this);
            }
            @Override
            public void visitAnnotatedExpression(JetAnnotatedExpression expression) {
                List<AnnotationDescriptor> resolved = resolveAnnotations(scope, expression.getAttributes(), trace);

                trace.record(BindingContext.ANNOTATION_EXPRESSION, expression, resolved);
                super.visitAnnotatedExpression(expression);
            }
        });
    }


    @NotNull
    public List<AnnotationDescriptor> resolveAnnotations(@NotNull JetScope scope, @Nullable JetModifierList modifierList, BindingTrace trace) {
        if (modifierList == null) {
            return Collections.emptyList();
        }
        return resolveAnnotations(scope, modifierList.getAnnotationEntries(), trace);
    }

    @NotNull
    public List<AnnotationDescriptor> resolveAnnotations(@NotNull JetScope scope, @NotNull List<JetAnnotationEntry> annotationEntryElements, BindingTrace trace) {
        if (annotationEntryElements.isEmpty()) return Collections.emptyList();
        List<AnnotationDescriptor> result = Lists.newArrayList();
        for (JetAnnotationEntry entryElement : annotationEntryElements) {
            AnnotationDescriptor descriptor = new AnnotationDescriptor();
            resolveAnnotationStub(scope, entryElement, descriptor, trace);
            result.add(descriptor);
        }
        return result;
    }

    public void resolveAnnotationStub(@NotNull JetScope scope, @NotNull JetAnnotationEntry entryElement,
            @NotNull AnnotationDescriptor descriptor, BindingTrace trace) {
        OverloadResolutionResults<FunctionDescriptor> results = resolveType(scope, entryElement, descriptor, trace);
        resolveArguments(results, descriptor, trace);
    }

    @NotNull
    private OverloadResolutionResults<FunctionDescriptor> resolveType(@NotNull JetScope scope,
            @NotNull JetAnnotationEntry entryElement,
            @NotNull AnnotationDescriptor descriptor, BindingTrace trace) {
        OverloadResolutionResults<FunctionDescriptor> results = callResolver.resolveCall(trace, scope, CallMaker.makeCall(ReceiverDescriptor.NO_RECEIVER, null, entryElement), NO_EXPECTED_TYPE, DataFlowInfo.EMPTY);
        JetType annotationType = results.getResultingDescriptor().getReturnType();
        if (results.isSuccess()) {
            descriptor.setAnnotationType(annotationType);
        } else {
            descriptor.setAnnotationType(ErrorUtils.createErrorType("Unresolved annotation type"));
        }
        return results;
    }

    private void resolveArguments(@NotNull OverloadResolutionResults<FunctionDescriptor> results,
            @NotNull AnnotationDescriptor descriptor, BindingTrace trace) {
        List<CompileTimeConstant<?>> arguments = Lists.newArrayList();
        for (Map.Entry<ValueParameterDescriptor, ResolvedValueArgument> descriptorToArgument :
                results.getResultingCall().getValueArguments().entrySet()) {
            // TODO: are varargs supported here?
            List<JetExpression> argumentExpressions = descriptorToArgument.getValue().getArgumentExpressions();
            ValueParameterDescriptor parameterDescriptor = descriptorToArgument.getKey();
            for (JetExpression argument : argumentExpressions) {
                arguments.add(resolveAnnotationArgument(argument, parameterDescriptor.getType(), trace));
            }
        }
        descriptor.setValueArguments(arguments);
    }

    @Nullable
    public CompileTimeConstant<?> resolveAnnotationArgument(@NotNull JetExpression expression, @NotNull final JetType expectedType, final BindingTrace trace) {
        JetVisitor<CompileTimeConstant<?>, Void> visitor = new JetVisitor<CompileTimeConstant<?>, Void>() {
            @Override
            public CompileTimeConstant<?> visitConstantExpression(JetConstantExpression expression, Void nothing) {
                JetType type = expressionTypingServices.getType(JetScope.EMPTY, expression, expectedType, DataFlowInfo.EMPTY, trace);
                if (type == null) {
                    // TODO:
                    //  trace.report(ANNOTATION_PARAMETER_SHOULD_BE_CONSTANT.on(expression));
                }
                return trace.get(BindingContext.COMPILE_TIME_VALUE, expression);
            }


            // @Override
//            public CompileTimeConstant visitAnnotation(JetAnnotation annotation, Void nothing) {
//                super.visitAnnotation(annotation, null); // TODO
//            }
//
//            @Override
//            public CompileTimeConstant visitAnnotationEntry(JetAnnotationEntry annotationEntry, Void nothing) {
//                return super.visitAnnotationEntry(annotationEntry, null); // TODO
//            }

            @Override
            public CompileTimeConstant<?> visitParenthesizedExpression(JetParenthesizedExpression expression, Void nothing) {
                JetExpression innerExpression = expression.getExpression();
                if (innerExpression == null) return null;
                return innerExpression.accept(this, null);
            }

            @Override
            public CompileTimeConstant<?> visitStringTemplateExpression(JetStringTemplateExpression expression,
                                                                        Void nothing) {
                return trace.get(BindingContext.COMPILE_TIME_VALUE, expression);
            }

            @Override
            public CompileTimeConstant<?> visitJetElement(JetElement element, Void nothing) {
                // TODO:
                //trace.report(ANNOTATION_PARAMETER_SHOULD_BE_CONSTANT.on(element));
                return null;
            }
        };
        return expression.accept(visitor, null);
    }

    @NotNull
    public List<AnnotationDescriptor> createAnnotationStubs(@Nullable JetModifierList modifierList, BindingTrace trace) {
        if (modifierList == null) {
            return Collections.emptyList();
        }
        return createAnnotationStubs(modifierList.getAnnotationEntries(), trace);
    }

    @NotNull
    public List<AnnotationDescriptor> createAnnotationStubs(List<JetAnnotationEntry> annotations, BindingTrace trace) {
        List<AnnotationDescriptor> result = Lists.newArrayList();
        for (JetAnnotationEntry annotation : annotations) {
            AnnotationDescriptor annotationDescriptor = new AnnotationDescriptor();
            result.add(annotationDescriptor);
            trace.record(BindingContext.ANNOTATION, annotation, annotationDescriptor);
        }
        return result;
    }
}
