package org.mvel2.util;

import org.mvel2.CompileException;
import org.mvel2.ParserContext;
import org.mvel2.ast.ASTNode;
import org.mvel2.ast.EndOfStatement;
import org.mvel2.ast.Proto;
import org.mvel2.compiler.ExecutableStatement;
import static org.mvel2.util.ParseTools.*;

import java.util.*;

public class ProtoParser {
    private char[] expr;
    private ParserContext pCtx;
    private int endOffset;

    private int cursor;
    private String protoName;

    String tk1 = null;
    String tk2 = null;

    private Class type;
    private String name;
    private String deferredName;

    private boolean interpreted = false;

    private ExecutionStack splitAccumulator;

    private static ThreadLocal<Queue<DeferredTypeResolve>> deferred = new ThreadLocal<Queue<DeferredTypeResolve>>();

    public ProtoParser(char[] expr, int offset, int offsetEnd, String protoName, ParserContext pCtx, int fields,
                       ExecutionStack splitAccumulator) {
        this.expr = expr;

        this.cursor = offset;
        this.endOffset = offsetEnd;

        this.protoName = protoName;
        this.pCtx = pCtx;

        this.interpreted = (ASTNode.COMPILE_IMMEDIATE & fields) == 0;

        this.splitAccumulator = splitAccumulator;
    }

    public Proto parse() {
        Proto proto = new Proto(protoName);

        notifyForLateResolution(proto);

        Mainloop:
        while (cursor < endOffset) {
            cursor = ParseTools.skipWhitespace(expr, cursor, pCtx);

            int start = cursor;

            if (tk2 == null) {
                while (cursor < endOffset && isIdentifierPart(expr[cursor])) cursor++;

                if (cursor > start) {
                    tk1 = new String(expr, start, cursor - start);

                    if ("def".equals(tk1) || "function".equals(tk1)) {
                        cursor++;
                        cursor = ParseTools.skipWhitespace(expr, cursor, pCtx);
                        start = cursor;
                        while (cursor < endOffset && isIdentifierPart(expr[cursor])) cursor++;

                        if (start == cursor) {
                            throw new CompileException("attempt to declare an anonymous function as a prototype member");
                        }

                        FunctionParser parser =
                                new FunctionParser(new String(expr, start, cursor - start),
                                        cursor, endOffset, expr, pCtx, null);

                        proto.declareReceiver(parser.getName(), parser.parse());
                        cursor = parser.getCursor() + 1;

                        tk1 = null;
                        continue;
                    }
                }

                cursor = ParseTools.skipWhitespace(expr, cursor, pCtx);
            }

            if (cursor > endOffset) {
                throw new CompileException("unexpected end of statement in proto declaration: " + protoName);
            }

            switch (expr[cursor]) {
                case ';':
                    cursor++;
                    calculateDecl();

                    if (interpreted && type == DeferredTypeResolve.class) {
                        /**
                         * If this type could not be immediately resolved, it may be a look-ahead case, so
                         * we defer resolution of the type until later and place it in the wait queue.
                         */
                        enqueueReceiverForLateResolution(deferredName, proto.declareReceiver(name, Proto.ReceiverType.DEFERRED, null));
                    }
                    else {
                        proto.declareReceiver(name, type, null);
                    }
                    break;

                case '=':
                    cursor++;
                    cursor = ParseTools.skipWhitespace(expr, cursor, pCtx);
                    start = cursor;

                    Loop:
                    while (cursor < endOffset) {
                        switch (expr[cursor]) {
                            case '{':
                            case '[':
                            case '(':
                            case '\'':
                            case '"':
                                cursor = balancedCaptureWithLineAccounting(expr, cursor, expr[cursor], pCtx);
                                break;

                            case ';':
                                break Loop;
                        }
                        cursor++;
                    }

                    calculateDecl();

                    ExecutableStatement initializer = (ExecutableStatement)
                            subCompileExpression(new String(expr, start, cursor++ - start), pCtx);

                    if (interpreted && type == DeferredTypeResolve.class) {
                        proto.declareReceiver(name, Proto.ReceiverType.DEFERRED, initializer);
                    }
                    else {
                        proto.declareReceiver(name, type, initializer);
                    }
                    break;

                default:
                    start = cursor;
                    while (cursor < endOffset && isIdentifierPart(expr[cursor])) cursor++;
                    if (cursor > start) {
                        tk2 = new String(expr, start, cursor - start);
                    }
            }
        }

        cursor++;

        /**
         * Check if the function is manually terminated.
         */
        if (splitAccumulator != null && ParseTools.isStatementNotManuallyTerminated(expr, cursor)) {
            /**
             * Add an EndOfStatement to the split accumulator in the parser.
             */
            splitAccumulator.add(new EndOfStatement());
        }

        return proto;
    }

    private void calculateDecl() {
        if (tk2 != null) {
            try {
                if (pCtx.hasProtoImport(tk1)) {
                    type = Proto.class;

                }
                else {
                    type = ParseTools.findClass(null, tk1, pCtx);
                }
                name = tk2;

            }
            catch (ClassNotFoundException e) {
                if (interpreted) {
                    type = DeferredTypeResolve.class;
                    deferredName = tk1;
                    name = tk2;
                }
                else {
                    throw new CompileException("could not resolve class: " + tk1, e);
                }
            }
        }
        else {
            type = Object.class;
            name = tk1;
        }

        tk1 = null;
        tk2 = null;
    }

    private interface DeferredTypeResolve {
        public boolean isWaitingFor(Proto proto);

        public String getName();
    }


    private void enqueueReceiverForLateResolution(final String name, final Proto.Receiver receiver) {
        Queue<DeferredTypeResolve> recv = deferred.get();
        if (recv == null) {
            deferred.set(recv = new LinkedList<DeferredTypeResolve>());
        }

        recv.add(new DeferredTypeResolve() {
            public boolean isWaitingFor(Proto proto) {
                return name.equals(proto.getName());
            }

            public String getName() {
                return name;
            }
        });

        // recv.add(receiver);
    }

    private void notifyForLateResolution(final Proto proto) {
        if (deferred.get() != null) {
            Queue<DeferredTypeResolve> recv = deferred.get();
            Set<DeferredTypeResolve> remove = new HashSet<DeferredTypeResolve>();
            for (DeferredTypeResolve r : recv) {
                if (r.isWaitingFor(proto)) remove.add(r);
            }

            for (DeferredTypeResolve r : remove) {
                recv.remove(r);
            }
        }
    }

    public int getCursor() {
        return cursor;
    }

    /**
     * This is such a horrible hack, but it's more performant than any other horrible hack I can think of
     * right now.
     *
     * @param expr
     * @param cursor
     * @param pCtx
     */
    public static void checkForPossibleUnresolvedViolations(char[] expr, int cursor, ParserContext pCtx) {
        if (isUnresolvedWaiting()) {
            LinkedHashMap<String, Object> imports =
                    (LinkedHashMap<String, Object>) pCtx.getParserConfiguration().getImports();

            Object o = imports.values().toArray()[imports.size() - 1];

            if (o instanceof Proto) {
                Proto proto = (Proto) o;

                int last = proto.getCursorEnd();
                cursor--;

                /**
                 * We walk backwards to ensure that the last valid statement was a proto declaration.
                 */

                while (cursor > last && ParseTools.isWhitespace(expr[cursor])) cursor--;
                while (cursor > last && ParseTools.isIdentifierPart(expr[cursor])) cursor--;
                while (cursor > last && (ParseTools.isWhitespace(expr[cursor]) || expr[cursor] == ';')) cursor--;

                if (cursor != last) {
                    throw new CompileException("unresolved reference (possible illegal forward-reference?): " +
                            ProtoParser.getNextUnresolvedWaiting(), expr, proto.getCursorStart());
                }
            }
        }
    }

    public static boolean isUnresolvedWaiting() {
        return deferred.get() != null && !deferred.get().isEmpty();
    }

    public static String getNextUnresolvedWaiting() {
        if (deferred.get() != null && !deferred.get().isEmpty()) {
            return deferred.get().poll().getName();
        }
        return null;
    }

}