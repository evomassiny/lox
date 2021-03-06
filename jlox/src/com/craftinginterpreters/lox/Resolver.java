/*
 * This Class resolves variable bindings: 
 * It binds each `Variable` expressions to a scope offset, relative to the execution
 * scope.
 *
 * Because lox uses a lexical scope, we can build those offset statically,
 * even before creating the environments themselves.
 *
 * To do so, we parse the AST in the order of its lexical definition,
 * not in the execution order.
 *
 * While we are traversing the AST, we opportinistically also check for
 * return statement and "this" expressions declared in the global scope.
 */

package com.craftinginterpreters.lox;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Stack;

class Resolver implements Expr.Visitor<Void>, Stmt.Visitor<Void> {
    // stores all binding resolution in a map inside the `interpretor`,
    // the map itself is indexed by expression,
    // which means whenever the interpreter wants to resolve a variable,
    // it will know in which scope it must perform the lookup, 
    // (relative to the execution current scope).
    private final Interpreter interpreter;
    // one map per scope,
    // keys are the variable name, values represent either or not the
    // variable was initialized.
    private final Stack<HashMap<String, Boolean>> scopes = new Stack<>();
    // either we are traversing a function definition, or we are in the global scope
    private FunctionType currentFunction = FunctionType.NONE;
    // either we are traversing a Class definition, or we are in the global scope
    private ClassType currentClass = ClassType.NONE;

    Resolver(Interpreter interpreter) {
        this.interpreter = interpreter;
    }

    private enum FunctionType {
        NONE,
        FUNCTION,
        INITIALIZER,
        METHOD
    }

    private enum ClassType {
        NONE,
        CLASS,
        SUBCLASS,
    }

    private void beginScope() {
        this.scopes.push(new HashMap<String, Boolean>());
    }

    private void endScope() {
        this.scopes.pop();
    }

    private void declare(Token name) {
        if (this.scopes.isEmpty()) return;
        Map<String, Boolean> scope = this.scopes.peek();
        if (scope.containsKey(name.lexeme)) {
            Lox.error(
                name,
                "Already a variable with this name in this scope."
            );
        }
        scope.put(name.lexeme, false); // false means uninitialized
    }

    private void define(Token name) {
        if (this.scopes.isEmpty()) return;
        this.scopes.peek().put(name.lexeme, true);
    }

    @Override
    public Void visitBlockStmt(Stmt.Block stmt) {
        this.beginScope();
        this.resolve(stmt.statements);
        this.endScope();
        return null;
    }

    @Override
    public Void visitClassStmt(Stmt.Class stmt) {
        ClassType enclosingClass = this.currentClass;
        this.currentClass = ClassType.CLASS;

        this.declare(stmt.name);
        this.define(stmt.name);

        // superclass
        if (stmt.superclass != null && stmt.name.lexeme.equals(stmt.superclass.name.lexeme)) {
            Lox.error(stmt.superclass.name, "A class cannot inherit from itself.");
        }
        if (stmt.superclass != null) {
            this.currentClass = ClassType.SUBCLASS;
            this.resolve(stmt.superclass);

            this.beginScope();
            this.scopes.peek().put("super", true); // mark "super" as defined
        }

        this.beginScope();
        this.scopes.peek().put("this", true);

        for (Stmt.Function method :  stmt.methods) {
            FunctionType declaration = FunctionType.METHOD;
            if (method.name.lexeme.equals("init")) {
                declaration = FunctionType.INITIALIZER;
            }
            this.resolveFunction(method, declaration);
        }
        this.endScope();

        if (stmt.superclass != null) this.endScope();

        this.currentClass = enclosingClass;
        return null;
    }

    @Override
    public Void visitExpressionStmt(Stmt.Expression stmt) {
        this.resolve(stmt.expression);
        return null;
    }

    @Override
    public Void visitIfStmt(Stmt.If stmt) {
        this.resolve(stmt.condition);
        this.resolve(stmt.thenBranch);
        if (stmt.elseBranch != null) {
            this.resolve(stmt.elseBranch);
        }
        return null;
    }

    @Override
    public Void visitPrintStmt(Stmt.Print stmt) {
        this.resolve(stmt.expression);
        return null;
    }

    @Override
    public Void visitReturnStmt(Stmt.Return stmt) {
        if (this.currentFunction == FunctionType.NONE) {
            Lox.error(stmt.keyword, "Can't return from top-level code.");
        }
        if (stmt.value != null ) {
            if (this.currentFunction == FunctionType.INITIALIZER) {
                Lox.error(stmt.keyword, "Can't return a value from an initializer.");
            }
            this.resolve(stmt.value);
        }
        return null;
    }

    @Override
    public Void visitVarStmt(Stmt.Var stmt) {
        this.declare(stmt.name);
        if (stmt.initializer != null) {
            this.resolve(stmt.initializer);
        }
        this.define(stmt.name);
        return null;
    }

    @Override
    public Void visitWhileStmt(Stmt.While stmt) {
        this.resolve(stmt.condition);
        this.resolve(stmt.body);
        return null;
    }


    @Override
    public Void visitBinaryExpr(Expr.Binary expr) {
        this.resolve(expr.left);
        this.resolve(expr.right);
        return null;
    }

    @Override
    public Void visitCallExpr(Expr.Call expr) {
        this.resolve(expr.callee);
        for (Expr argument: expr.arguments) {
            this.resolve(argument);
        }
        return null;
    }

    @Override
    public Void visitGetExpr(Expr.Get expr) {
        // recursively resolve class instance
        this.resolve(expr.object);
        return null;
    }

    @Override
    public Void visitGroupingExpr(Expr.Grouping expr) {
        this.resolve(expr.expression);
        return null;
    }

    @Override
    public Void visitLiteralExpr(Expr.Literal expr) {
        return null;
    }

    @Override
    public Void visitLogicalExpr(Expr.Logical expr) {
        this.resolve(expr.left);
        this.resolve(expr.right);
        return null;
    }

    @Override
    public Void visitSetExpr(Expr.Set expr) {
        this.resolve(expr.value);
        this.resolve(expr.object);
        return null;
    }

    @Override
    public Void visitSuperExpr(Expr.Super expr) {
        if (this.currentClass == ClassType.NONE) {
            Lox.error(expr.keyword, "Can't use 'super' outside of class.");
        }
        if (this.currentClass != ClassType.SUBCLASS) {
            Lox.error(expr.keyword, "Can't use 'super' in a class without superclass.");
        }
        this.resolveLocal(expr, expr.keyword);
        return null;
    }

    @Override
    public Void visitThisExpr(Expr.This expr) {
        if (this.currentClass == ClassType.NONE) {
            Lox.error(expr.keyword, "Can't use 'this' outside of a class definition.");
            return null;
        }
        this.resolveLocal(expr, expr.keyword);
        return null;
    }

    @Override
    public Void visitUnaryExpr(Expr.Unary expr) {
        this.resolve(expr.right);
        return null;
    }

    @Override
    public Void visitVariableExpr(Expr.Variable expr) {
        if (!this.scopes.isEmpty() &&
                this.scopes.peek().get(expr.name.lexeme) == Boolean.FALSE) {
            Lox.error(
                    expr.name,
                    "Can't read local variable in its own initializer.");
        }
        this.resolveLocal(expr, expr.name);
        return null;
    }

    @Override
    public Void visitAssignExpr(Expr.Assign expr) {
        this.resolve(expr.value); // value is an expression
        this.resolveLocal(expr, expr.name);
        return null;
    }

    @Override
    public Void visitFunctionStmt(Stmt.Function stmt) {
        // function name is resolved before its body,
        // for recursive functions
        this.declare(stmt.name);
        this.define(stmt.name);
        this.resolveFunction(stmt, FunctionType.FUNCTION);
        return null;
    }

    private void resolveFunction(Stmt.Function function, FunctionType type) {
        // stash previous kind
        FunctionType enclosingFunction = this.currentFunction;
        this.currentFunction = type;
        // function name already in outer scope
        this.beginScope();
        // add arguments to scope
        for (Token name: function.params) {
            this.declare(name);
            this.define(name);
        }
        this.resolve(function.body);
        this.endScope();
        // restore previous kind
        this.currentFunction = enclosingFunction;
    }

    private void resolveLocal(Expr expr, Token name) {
        for (int i = this.scopes.size() - 1; i >= 0; i--) {
            if (this.scopes.get(i).containsKey(name.lexeme)) {
                // the number of hops needed to resolve 
                // name, relative to the current one.
                int scope_hop_count = this.scopes.size() - 1 - i;
                // if I'm correct, expr can only be an Expr.Variable
                this.interpreter.resolve(expr, scope_hop_count);
            }
        }
    }

    void resolve(List<Stmt> statements) {
        for (Stmt statement: statements) {
            this.resolve(statement);
        }
    }

    private void resolve(Stmt stmt) {
        // Visitor pattern
        stmt.accept(this);
    }

    private void resolve(Expr expr) {
        // Visitor pattern
        expr.accept(this);
    }
}
