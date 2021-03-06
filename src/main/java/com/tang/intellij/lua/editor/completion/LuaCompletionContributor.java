/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
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

package com.tang.intellij.lua.editor.completion;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.lang.parser.GeneratedParserUtilBase;
import com.intellij.openapi.project.Project;
import com.intellij.patterns.PsiElementPattern;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.impl.source.tree.TreeUtil;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.util.ProcessingContext;
import com.intellij.util.containers.HashSet;
import com.tang.intellij.lua.highlighting.LuaSyntaxHighlighter;
import com.tang.intellij.lua.lang.LuaIcons;
import com.tang.intellij.lua.lang.LuaLanguage;
import com.tang.intellij.lua.psi.*;
import com.tang.intellij.lua.stubs.index.LuaGlobalFuncIndex;
import com.tang.intellij.lua.stubs.index.LuaGlobalVarIndex;
import org.jetbrains.annotations.NotNull;

import static com.intellij.patterns.PlatformPatterns.psiElement;

/**
 *
 * Created by tangzx on 2016/11/27.
 */
public class LuaCompletionContributor extends CompletionContributor {

    private static final PsiElementPattern.Capture<PsiElement> SHOW_CLASS_METHOD = psiElement(LuaTypes.ID)
            .withParent(LuaCallExpr.class);
    private static final PsiElementPattern.Capture<PsiElement> SHOW_CLASS_FIELD = psiElement(LuaTypes.ID)
            .withParent(LuaIndexExpr.class);
    private static final PsiElementPattern.Capture<PsiElement> IN_COMMENT = psiElement().inside(psiElement().withElementType(LuaTypes.DOC_COMMENT));
    private static final PsiElementPattern.Capture<PsiElement> SHOW_OVERRIDE = psiElement().withParent(LuaClassMethodName.class);
    private static final PsiElementPattern.Capture<PsiElement> SHOW_PATH = psiElement(LuaTypes.STRING).inside(
            psiElement(LuaTypes.ARGS).afterLeaf("require")
    );

    public LuaCompletionContributor() {
        //可以override
        extend(CompletionType.BASIC, SHOW_OVERRIDE, new OverrideCompletionProvider());

        //提示方法
        extend(CompletionType.BASIC, SHOW_CLASS_METHOD, new ClassMethodCompletionProvider());

        //提示属性
        extend(CompletionType.BASIC, SHOW_CLASS_FIELD, new ClassFieldCompletionProvider());

        extend(CompletionType.BASIC, SHOW_PATH, new RequirePathCompletionProvider());

        //提示全局函数,local变量,local函数
        extend(CompletionType.BASIC, psiElement().inside(LuaFile.class)
                .andNot(SHOW_CLASS_METHOD)
                .andNot(SHOW_CLASS_FIELD)
                .andNot(IN_COMMENT)
                .andNot(SHOW_OVERRIDE), new CompletionProvider<CompletionParameters>() {
            @Override
            protected void addCompletions(@NotNull CompletionParameters completionParameters, ProcessingContext processingContext, @NotNull CompletionResultSet completionResultSet) {
                //local
                PsiElement cur = completionParameters.getOriginalFile().findElementAt(completionParameters.getOffset() - 1);
                LuaPsiTreeUtil.walkUpLocalNameDef(cur, nameDef -> {
                    String name = nameDef.getText();
                    if (completionResultSet.getPrefixMatcher().prefixMatches(name)) {
                        LookupElementBuilder elementBuilder = LookupElementBuilder.create(name)
                                .withIcon(LuaIcons.LOCAL_VAR);
                        completionResultSet.addElement(elementBuilder);
                    }
                    return  true;
                });
                LuaPsiTreeUtil.walkUpLocalFuncDef(cur, localFuncDef -> {
                    String name = localFuncDef.getName();
                    if (name != null && completionResultSet.getPrefixMatcher().prefixMatches(name)) {
                        LookupElementBuilder elementBuilder = LookupElementBuilder.create(localFuncDef.getName())
                                .withInsertHandler(new FuncInsertHandler(localFuncDef))
                                .withIcon(LuaIcons.LOCAL_FUNCTION);
                        completionResultSet.addElement(elementBuilder);
                    }
                    return true;
                });

                //global functions
                Project project = completionParameters.getOriginalFile().getProject();
                LuaGlobalFuncIndex.getInstance().processAllKeys(project, name -> {
                    if (completionResultSet.getPrefixMatcher().prefixMatches(name)) {
                        LookupElementBuilder elementBuilder = LookupElementBuilder.create(name)
                                .withTypeText("Global Func")
                                .withInsertHandler(new GlobalFuncInsertHandler(name, project))
                                .withIcon(LuaIcons.GLOBAL_FUNCTION);
                        completionResultSet.addElement(elementBuilder);
                    }
                    return true;
                });

                //global fields
                LuaGlobalVarIndex.getInstance().processAllKeys(project, name -> {
                    if (completionResultSet.getPrefixMatcher().prefixMatches(name)) {
                        completionResultSet.addElement(LookupElementBuilder.create(name).withIcon(LuaIcons.GLOBAL_FIELD));
                    }
                    return true;
                });

                //key words
                TokenSet keywords = TokenSet.orSet(LuaSyntaxHighlighter.KEYWORD_TOKENS, LuaSyntaxHighlighter.PRIMITIVE_TYPE_SET);
                keywords = TokenSet.orSet(TokenSet.create(LuaTypes.SELF), keywords);
                for (IElementType keyWordToken : keywords.getTypes()) {
                    completionResultSet.addElement(LookupElementBuilder.create(keyWordToken)
                            .withInsertHandler(new KeywordInsertHandler(keyWordToken))
                    );
                }

                //words in file
                suggestWordsInFile(completionParameters, processingContext, completionResultSet);
            }
        });
    }

    static void suggestWordsInFile(@NotNull CompletionParameters completionParameters, ProcessingContext processingContext, @NotNull CompletionResultSet completionResultSet) {
        HashSet<String> wordsInFileSet = new HashSet<>();
        PsiFile file = completionParameters.getOriginalFile();
        file.acceptChildren(new LuaVisitor() {
            @Override
            public void visitPsiElement(@NotNull LuaPsiElement o) {
                o.acceptChildren(this);
            }

            @Override
            public void visitElement(PsiElement element) {
                if (element.getNode().getElementType() == LuaTypes.ID && element.getTextLength() > 2) {
                    String text = element.getText();
                    if (completionResultSet.getPrefixMatcher().prefixMatches(text))
                        wordsInFileSet.add(text);
                }
                super.visitElement(element);
            }
        });

        for (String s : wordsInFileSet) {
            completionResultSet.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder
                            .create(s)
                            .withIcon(LuaIcons.WORD)
                            .withTypeText("Word In File")
                    , -1));
        }
    }

    private static void suggestKeywords(PsiElement position) {

        GeneratedParserUtilBase.CompletionState state = new GeneratedParserUtilBase.CompletionState(8) {
            @Override
            public String convertItem(Object o) {
                if (o instanceof LuaTokenType) {
                    LuaTokenType tokenType = (LuaTokenType) o;
                    return tokenType.toString();
                }
                // we do not have other keywords
                return o instanceof String? (String)o : null;
            }
        };

        PsiFileFactory psiFileFactory = PsiFileFactory.getInstance(position.getProject());
        PsiFile file = psiFileFactory.createFileFromText("a.lua", LuaLanguage.INSTANCE, "local ", true, false);
        file.putUserData(GeneratedParserUtilBase.COMPLETION_STATE_KEY, state);
        TreeUtil.ensureParsed(file.getNode());

        System.out.println("---");
    }
}
