/*
 *
 * Copyright(c) 2015, Samsung Electronics Co., Ltd.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name of the <organization> nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.
    
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */ 

package org.apache.lucene.analysis.miscellaneous;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.util.CharArraySet;
import org.apache.lucene.analysis.util.Lucene43FilteringTokenFilter;

/**
 * Backcompat for KeepWordFilter for versions 4.3 and before.
 * @deprecated Use {@link org.apache.lucene.analysis.miscellaneous.KeepWordFilter}
 */
@Deprecated
public final class Lucene43KeepWordFilter extends Lucene43FilteringTokenFilter {
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  private final CharArraySet words;

  /** The words set passed to this constructor will be directly used by this filter
   * and should not be modified, */
  public Lucene43KeepWordFilter(boolean enablePositionIncrements, TokenStream in, CharArraySet words) {
    super(enablePositionIncrements, in);
    this.words = words;
  }

  public boolean accept() {
    return words.contains(termAtt.buffer(), 0, termAtt.length());
  }
}