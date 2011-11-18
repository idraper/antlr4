/*
 [The "BSD license"]
 Copyright (c) 2005-2009 Terence Parr
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions
 are met:
 1. Redistributions of source code must retain the above copyright
     notice, this list of conditions and the following disclaimer.
 2. Redistributions in binary form must reproduce the above copyright
     notice, this list of conditions and the following disclaimer in the
     documentation and/or other materials provided with the distribution.
 3. The name of the author may not be used to endorse or promote products
     derived from this software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.antlr.v4.runtime.tree;

import org.antlr.v4.runtime.*;

import java.util.*;

/** A buffered stream of tree nodes.  Nodes can be from a tree of ANY kind.
 *
 *  This node stream sucks all nodes out of the tree specified in
 *  the constructor during construction and makes pointers into
 *  the tree using an array of Object pointers. The stream necessarily
 *  includes pointers to DOWN and UP and EOF nodes.
 *
 *  This stream knows how to mark/release for backtracking.
 *
 *  This stream is most suitable for tree interpreters that need to
 *  jump around a lot or for tree parsers requiring speed (at cost of memory).
 *  There is some duplicated functionality here with UnBufferedASTNodeStream
 *  but just in bookkeeping, not tree walking etc...
 *
 *  TARGET DEVELOPERS:
 *
 *  This is the old CommonASTNodeStream that buffered up entire node stream.
 *  No need to implement really as new CommonASTNodeStream is much better
 *  and covers what we need.
 *
 *  @see CommonASTNodeStream
 */
public class BufferedASTNodeStream<T> implements ASTNodeStream<T> {
	public static final int DEFAULT_INITIAL_BUFFER_SIZE = 100;
	public static final int INITIAL_CALL_STACK_SIZE = 10;

    protected class StreamIterator implements Iterator<T> {
		int i = 0;
		@Override
		public boolean hasNext() {
			return i<nodes.size();
		}

		@Override
		public T next() {
			int current = i;
			i++;
			if ( current < nodes.size() ) {
				return nodes.get(current);
			}
			return eof;
		}

		@Override
		public void remove() {
			throw new RuntimeException("cannot remove nodes from stream");
		}
	}

	// all these navigation nodes are shared and hence they
	// cannot contain any line/column info

	protected T down;
	protected T up;
	protected T eof;

	/** The complete mapping from stream index to tree node.
	 *  This buffer includes pointers to DOWN, UP, and EOF nodes.
	 *  It is built upon ctor invocation.  The elements are type
	 *  Object as we don't what the trees look like.
	 *
	 *  Load upon first need of the buffer so we can set token types
	 *  of interest for reverseIndexing.  Slows us down a wee bit to
	 *  do all of the if p==-1 testing everywhere though.
	 */
	protected List<T> nodes;

	/** Pull nodes from which tree? */
	protected T root;

	/** IF this tree (root) was created from a token stream, track it. */
	protected TokenStream tokens;

	/** What tree adaptor was used to build these trees */
	ASTAdaptor<T> adaptor;

	/** Reuse same DOWN, UP navigation nodes unless this is true */
	protected boolean uniqueNavigationNodes = false;

	/** The index into the nodes list of the current node (next node
	 *  to consume).  If -1, nodes array not filled yet.
	 */
	protected int p = -1;

	/** Track the last mark() call result value for use in rewind(). */
	protected int lastMarker;

	/** Stack of indexes used for push/pop calls */
	protected List<Integer> calls;

	public BufferedASTNodeStream(T tree) {
		this(new CommonASTAdaptor(), tree);
	}

	public BufferedASTNodeStream(ASTAdaptor adaptor, T tree) {
		this(adaptor, tree, DEFAULT_INITIAL_BUFFER_SIZE);
	}

	public BufferedASTNodeStream(ASTAdaptor<T> adaptor, T tree, int initialBufferSize) {
		this.root = tree;
		this.adaptor = adaptor;
		nodes = new ArrayList<T>(initialBufferSize);
		down = adaptor.create(Token.DOWN, "DOWN");
		up = adaptor.create(Token.UP, "UP");
		eof = adaptor.create(Token.EOF, "EOF");
	}

	/** Walk tree with depth-first-search and fill nodes buffer.
	 *  Don't do DOWN, UP nodes if its a list (t is isNil).
	 */
	protected void fillBuffer() {
		fillBuffer(root);
		//System.out.println("revIndex="+tokenTypeToStreamIndexesMap);
		p = 0; // buffer of nodes intialized now
	}

	public void fillBuffer(T t) {
		boolean nil = adaptor.isNil(t);
		if ( !nil ) {
			nodes.add(t); // add this node
		}
		// add DOWN node if t has children
		int n = adaptor.getChildCount(t);
		if ( !nil && n>0 ) {
			addNavigationNode(Token.DOWN);
		}
		// and now add all its children
		for (int c=0; c<n; c++) {
			T child = (T)adaptor.getChild(t,c);
			fillBuffer(child);
		}
		// add UP node if t has children
		if ( !nil && n>0 ) {
			addNavigationNode(Token.UP);
		}
	}

	/** What is the stream index for node? 0..n-1
	 *  Return -1 if node not found.
	 */
	protected int getNodeIndex(T node) {
		if ( p==-1 ) {
			fillBuffer();
		}
		for (int i = 0; i < nodes.size(); i++) {
			T t = (T) nodes.get(i);
			if ( t==node ) {
				return i;
			}
		}
		return -1;
	}

	/** As we flatten the tree, we use UP, DOWN nodes to represent
	 *  the tree structure.  When debugging we need unique nodes
	 *  so instantiate new ones when uniqueNavigationNodes is true.
	 */
	protected void addNavigationNode(final int ttype) {
		T navNode = null;
		if ( ttype==Token.DOWN ) {
			if ( hasUniqueNavigationNodes() ) {
				navNode = adaptor.create(Token.DOWN, "DOWN");
			}
			else {
				navNode = down;
			}
		}
		else {
			if ( hasUniqueNavigationNodes() ) {
				navNode = adaptor.create(Token.UP, "UP");
			}
			else {
				navNode = up;
			}
		}
		nodes.add((T)navNode);
	}

	@Override
	public T get(int i) {
		if ( p==-1 ) {
			fillBuffer();
		}
		return nodes.get(i);
	}

	public List<T> get(int i, int j) {
		if ( p==-1 ) {
			fillBuffer();
		}
		return nodes.subList(i,j+1);
	}

	public List<T> get(T start, T stop) {
		int i=0;
		for (; i<nodes.size(); i++) {
			T t = nodes.get(i);
			if ( t==start ) break;
		}
		int j=i;
		for (; j<nodes.size(); j++) {
			T t = nodes.get(j);
			if ( t==stop ) break;
		}
		if ( i>=nodes.size() ) return null;
		if ( j>=nodes.size() ) j = nodes.size()-1;
		return get(i,j);
	}

	@Override
	public T LT(int k) {
		if ( p==-1 ) {
			fillBuffer();
		}
		if ( k==0 ) {
			return null;
		}
		if ( k<0 ) {
			return LB(-k);
		}
		//System.out.print("LT(p="+p+","+k+")=");
		if ( (p+k-1) >= nodes.size() ) {
			return eof;
		}
		return nodes.get(p+k-1);
	}

	public T getCurrentSymbol() { return LT(1); }

/*
	public T getLastTreeNode() {
		int i = index();
		if ( i>=size() ) {
			i--; // if at EOF, have to start one back
		}
		System.out.println("start last node: "+i+" size=="+nodes.size());
		while ( i>=0 &&
			(adaptor.getType(get(i))==Token.EOF ||
			 adaptor.getType(get(i))==Token.UP ||
			 adaptor.getType(get(i))==Token.DOWN) )
		{
			i--;
		}
		System.out.println("stop at node: "+i+" "+nodes.get(i));
		return nodes.get(i);
	}
*/

	/** Look backwards k nodes */
	protected T LB(int k) {
		if ( k==0 ) {
			return null;
		}
		if ( (p-k)<0 ) {
			return null;
		}
		return nodes.get(p-k);
	}

	@Override
	public T getTreeSource() {
		return root;
	}

	@Override
	public String getSourceName() {
		return getTokenStream().getSourceName();
	}

	@Override
	public ASTAdaptor getTreeAdaptor() {
		return adaptor;
	}

	@Override
	public TokenStream getTokenStream() {
		return tokens;
	}

	public void setTokenStream(TokenStream tokens) {
		this.tokens = tokens;
	}

	public ASTAdaptor getASTAdaptor() {
		return adaptor;
	}

	public void setASTAdaptor(ASTAdaptor adaptor) {
		this.adaptor = adaptor;
	}

	public boolean hasUniqueNavigationNodes() {
		return uniqueNavigationNodes;
	}

	@Override
	public void setUniqueNavigationNodes(boolean uniqueNavigationNodes) {
		this.uniqueNavigationNodes = uniqueNavigationNodes;
	}

	@Override
	public void consume() {
		if ( p==-1 ) {
			fillBuffer();
		}
		p++;
	}

	@Override
	public int LA(int i) {
		return adaptor.getType(LT(i));
	}

	@Override
	public int mark() {
		if ( p==-1 ) {
			fillBuffer();
		}
		lastMarker = index();
		return lastMarker;
	}

	@Override
	public void release(int marker) {
		// no resources to release
	}

	@Override
	public int index() {
		return p;
	}

	public void rewind(int marker) {
		seek(marker);
	}

	public void rewind() {
		seek(lastMarker);
	}

	@Override
	public void seek(int index) {
		if ( p==-1 ) {
			fillBuffer();
		}
		p = index;
	}

	/** Make stream jump to a new location, saving old location.
	 *  Switch back with pop().
	 */
	public void push(int index) {
		if ( calls==null ) {
			calls = new ArrayList<Integer>();
		}
		calls.add(p); // save current index
		seek(index);
	}

	/** Seek back to previous index saved during last push() call.
	 *  Return top of stack (return index).
	 */
	public int pop() {
		int ret = calls.remove(calls.size() - 1);
		seek(ret);
		return ret;
	}

	@Override
	public void reset() {
		p = 0;
		lastMarker = 0;
        if (calls != null) {
            calls.clear();
        }
    }

	@Override
	public int size() {
		if ( p==-1 ) {
			fillBuffer();
		}
		return nodes.size();
	}

	public Iterator iterator() {
		if ( p==-1 ) {
			fillBuffer();
		}
		return new StreamIterator();
	}

	// TREE REWRITE INTERFACE

	@Override
	public void replaceChildren(T parent, int startChildIndex, int stopChildIndex, T t) {
		if ( parent!=null ) {
			adaptor.replaceChildren(parent, startChildIndex, stopChildIndex, t);
		}
	}

	/** Used for testing, just return the token type stream */
	public String toTokenTypeString() {
		if ( p==-1 ) {
			fillBuffer();
		}
		StringBuffer buf = new StringBuffer();
		for (int i = 0; i < nodes.size(); i++) {
			T t = (T) nodes.get(i);
			buf.append(" ");
			buf.append(adaptor.getType(t));
		}
		return buf.toString();
	}

	/** Debugging */
	public String toTokenString(int start, int stop) {
		if ( p==-1 ) {
			fillBuffer();
		}
		StringBuffer buf = new StringBuffer();
		for (int i = start; i < nodes.size() && i <= stop; i++) {
			T t = (T) nodes.get(i);
			buf.append(" ");
			buf.append(adaptor.getToken(t));
		}
		return buf.toString();
	}

	@Override
	public String toString(T start, T stop) {
		System.out.println("toString");
		if ( start==null || stop==null ) {
			return null;
		}
		if ( p==-1 ) {
			fillBuffer();
		}
		//System.out.println("stop: "+stop);
		if ( start instanceof CommonAST )
			System.out.print("toString: "+((CommonAST)start).getToken()+", ");
		else
			System.out.println(start);
		if ( stop instanceof CommonAST )
			System.out.println(((CommonAST)stop).getToken());
		else
			System.out.println(stop);
		// if we have the token stream, use that to dump text in order
		if ( tokens!=null ) {
			int beginTokenIndex = adaptor.getTokenStartIndex(start);
			int endTokenIndex = adaptor.getTokenStopIndex(stop);
			// if it's a tree, use start/stop index from start node
			// else use token range from start/stop nodes
			if ( adaptor.getType(stop)==Token.UP ) {
				endTokenIndex = adaptor.getTokenStopIndex(start);
			}
			else if ( adaptor.getType(stop)==Token.EOF ) {
				endTokenIndex = size()-2; // don't use EOF
			}
			return tokens.toString(beginTokenIndex, endTokenIndex);
		}
		// walk nodes looking for start
		T t = null;
		int i = 0;
		for (; i < nodes.size(); i++) {
			t = nodes.get(i);
			if ( t==start ) {
				break;
			}
		}
		// now walk until we see stop, filling string buffer with text
		 StringBuffer buf = new StringBuffer();
		t = nodes.get(i);
		while ( t!=stop ) {
			String text = adaptor.getText(t);
			if ( text==null ) {
				text = " "+String.valueOf(adaptor.getType(t));
			}
			buf.append(text);
			i++;
			t = nodes.get(i);
		}
		// include stop node too
		String text = adaptor.getText(stop);
		if ( text==null ) {
			text = " "+String.valueOf(adaptor.getType(stop));
		}
		buf.append(text);
		return buf.toString();
	}
}
