/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package com.intellij.codeInsight.editorActions;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RawText;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.LineTokenizer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.intellij.openapi.util.text.StringUtil.unescapeStringCharacters;

public class StringLiteralCopyPasteProcessor implements CopyPastePreProcessor {
  
  private static final TokenSet SYMBOL_LITERAL_TYPES = TokenSet.create(JavaTokenType.STRING_LITERAL, JavaTokenType.CHARACTER_LITERAL);
  
  @SuppressWarnings("ForLoopThatDoesntUseLoopVariable")
  @Override
  public String preprocessOnCopy(final PsiFile file, final int[] startOffsets, final int[] endOffsets, final String text) {
    // The main idea is to un-escape string/char literals content if necessary.
    // Example:
    //    Suppose we have a following text at the editor: String s = "first <selection>line \n second</selection> line"
    //    When user copies the selection we want to transform text \n to the real line feed, i.e. clipboard should contains the following:
    //        line 
    //        second
    //
    // However, we don't want to un-escape literal content if it's copied completely.
    // Example:
    //     String s = <selection>"my string"</selection>;
    
    StringBuilder buffer = new StringBuilder();
    int givenTextOffset = 0;
    boolean textWasChanged = false;
    for (int i = 0; i < startOffsets.length && givenTextOffset < text.length(); i++, givenTextOffset++) {
      if (i > 0) {
        buffer.append('\n'); // LF is added for block selection
      }
      // Calculate offsets offsets of the selection interval being processed now.
      final int fileStartOffset = startOffsets[i];
      final int fileEndOffset = endOffsets[i];
      int givenTextStartOffset = Math.min(givenTextOffset, text.length());
      final int givenTextEndOffset = Math.min(givenTextOffset + (fileEndOffset - fileStartOffset), text.length());
      givenTextOffset = givenTextEndOffset;
      for (
        PsiElement element = file.findElementAt(fileStartOffset);
        givenTextStartOffset < givenTextEndOffset;
        element = PsiTreeUtil.nextLeaf(element)) {
        if (element == null) {
          buffer.append(text.substring(givenTextStartOffset, givenTextEndOffset));
          break;
        }
        TextRange elementRange = element.getTextRange();
        int escapedStartOffset;
        int escapedEndOffset;
        if (element instanceof PsiJavaToken && SYMBOL_LITERAL_TYPES.contains(((PsiJavaToken)element).getTokenType())
            // We don't want to un-escape if complete literal is copied.
            && (elementRange.getStartOffset() < fileStartOffset || elementRange.getEndOffset() > fileEndOffset)) {
          escapedStartOffset = elementRange.getStartOffset() + 1 /* String/char literal quote */;
          escapedEndOffset = elementRange.getEndOffset() - 1 /* String/char literal quote */;
        }
        else {
          escapedStartOffset = escapedEndOffset = elementRange.getStartOffset();
        }

        // Process text to the left of the escaped fragment (if any).
        int numberOfSymbolsToCopy = escapedStartOffset - Math.max(fileStartOffset, elementRange.getStartOffset());
        if (numberOfSymbolsToCopy > 0) {
          buffer.append(text.substring(givenTextStartOffset, givenTextStartOffset + numberOfSymbolsToCopy));
          givenTextStartOffset += numberOfSymbolsToCopy;
        }

        // Process escaped text (un-escape it).
        numberOfSymbolsToCopy = Math.min(escapedEndOffset, fileEndOffset) - Math.max(fileStartOffset, escapedStartOffset);
        if (numberOfSymbolsToCopy > 0) {
          textWasChanged = true;
          buffer.append(unescapeStringCharacters(text.substring(givenTextStartOffset, givenTextStartOffset + numberOfSymbolsToCopy)));
          givenTextStartOffset += numberOfSymbolsToCopy;
        }

        // Process text to the right of the escaped fragment (if any).
        numberOfSymbolsToCopy = Math.min(fileEndOffset, elementRange.getEndOffset()) - Math.max(fileStartOffset, escapedEndOffset);
        if (numberOfSymbolsToCopy > 0) {
          buffer.append(text.substring(givenTextStartOffset, givenTextStartOffset + numberOfSymbolsToCopy));
          givenTextStartOffset += numberOfSymbolsToCopy;
        }
      }
    }
    return textWasChanged ? buffer.toString() : null;
  }
  
  public String preprocessOnPaste(final Project project, final PsiFile file, final Editor editor, String text, final RawText rawText) {
    final Document document = editor.getDocument();
    PsiDocumentManager.getInstance(project).commitDocument(document);
    final SelectionModel selectionModel = editor.getSelectionModel();

    // pastes in block selection mode (column mode) are not handled by a CopyPasteProcessor
    final int selectionStart = selectionModel.getSelectionStart();
    final int selectionEnd = selectionModel.getSelectionEnd();
    IElementType tokenType = findLiteralTokenType(file, selectionStart, selectionEnd);

    if (tokenType == JavaTokenType.STRING_LITERAL) {
      if (rawText != null && rawText.rawText != null) return rawText.rawText; // Copied from the string literal. Copy as is.

      StringBuilder buffer = new StringBuilder(text.length());
      CodeStyleSettings codeStyleSettings = CodeStyleSettingsManager.getSettings(project);
      @NonNls String breaker = codeStyleSettings.BINARY_OPERATION_SIGN_ON_NEXT_LINE ? "\\n\"\n+ \"" : "\\n\" +\n\"";
      final String[] lines = LineTokenizer.tokenize(text.toCharArray(), false, true);
      for (int i = 0; i < lines.length; i++) {
        String line = lines[i];
        buffer.append(StringUtil.escapeStringCharacters(line));
        if (i != lines.length - 1) buffer.append(breaker);
      }
      text = buffer.toString();
    }
    else if (tokenType == JavaTokenType.CHARACTER_LITERAL) {
      if (rawText != null && rawText.rawText != null) return rawText.rawText; // Copied from the string literal. Copy as is.
      return escapeCharCharacters(text);
    }
    return text;
  }

  @Nullable
  private static IElementType findLiteralTokenType(PsiFile file, int selectionStart, int selectionEnd) {
    final PsiElement elementAtSelectionStart = file.findElementAt(selectionStart);
    if (!(elementAtSelectionStart instanceof PsiJavaToken)) {
      return null;
    }
    final IElementType tokenType = ((PsiJavaToken)elementAtSelectionStart).getTokenType();
    if ((tokenType != JavaTokenType.STRING_LITERAL && tokenType != JavaTokenType.CHARACTER_LITERAL)) {
      return null;
    }

    if (elementAtSelectionStart.getTextRange().getEndOffset() < selectionEnd) {
      final PsiElement elementAtSelectionEnd = file.findElementAt(selectionEnd);
      if (!(elementAtSelectionEnd instanceof PsiJavaToken)) {
        return null;
      }
      PsiJavaToken tokenAtSelectionEnd = (PsiJavaToken)elementAtSelectionEnd;
      if (tokenAtSelectionEnd.getTokenType() == tokenType && tokenAtSelectionEnd.getTextRange().getStartOffset() < selectionEnd) {
        return tokenType;
      }
    }
    
    final TextRange textRange = elementAtSelectionStart.getTextRange();
    if (selectionStart <= textRange.getStartOffset() || selectionEnd >= textRange.getEndOffset()) {
      return null;
    }
    return tokenType;
  }

  @NotNull
  public static String escapeCharCharacters(@NotNull String s) {
    StringBuilder buffer = new StringBuilder();
    StringUtil.escapeStringCharacters(s.length(), s, "\'", buffer);
    return buffer.toString();
  }
}
