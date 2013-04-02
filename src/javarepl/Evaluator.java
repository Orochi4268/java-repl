package javarepl;

import com.googlecode.totallylazy.Either;
import com.googlecode.totallylazy.Files;
import com.googlecode.totallylazy.Option;
import com.googlecode.totallylazy.annotations.multimethod;
import com.googlecode.totallylazy.multi;
import javarepl.expressions.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.net.URL;
import java.util.List;

import static com.googlecode.totallylazy.Callables.toString;
import static com.googlecode.totallylazy.Either.left;
import static com.googlecode.totallylazy.Either.right;
import static com.googlecode.totallylazy.Files.file;
import static com.googlecode.totallylazy.Files.temporaryDirectory;
import static com.googlecode.totallylazy.Option.some;
import static com.googlecode.totallylazy.Sequences.sequence;
import static com.googlecode.totallylazy.URLs.toURL;
import static java.io.File.pathSeparator;
import static javarepl.Evaluation.evaluation;
import static javarepl.EvaluationContext.emptyEvaluationContext;
import static javarepl.Utils.randomIdentifier;
import static javarepl.expressions.ExpressionPatterns.*;
import static javarepl.rendering.EvaluationClassRenderer.renderExpressionClass;
import static javax.tools.ToolProvider.getSystemJavaCompiler;

public class Evaluator {
    private final File outputDirectory = temporaryDirectory("JavaREPL");
    private final EvaluationClassLoader classLoader = new EvaluationClassLoader(new URL[]{toURL().apply(outputDirectory)});

    private EvaluationContext context = emptyEvaluationContext();

    public Either<? extends Throwable, Evaluation> evaluate(String expr) {
        Option<Evaluation> evaluationOption = context.evaluationForResult(expr);
        if (!evaluationOption.isEmpty()) {
            return right(evaluationOption.get());
        }

        Either<? extends Throwable, Evaluation> result = evaluate(createExpression(expr));
        if (result.isLeft() && result.left() instanceof ExpressionCompilationException) {
            result = evaluate(new Statement(expr));
        }

        return result;
    }

    public Option<Evaluation> lastEvaluation() {
        return context.lastEvaluation();
    }

    public List<Result> results() {
        return context.results().toList();
    }

    public List<Evaluation> classes() {
        return context.classes().toList();
    }

    public void clear() {
        context = emptyEvaluationContext();
    }

    public void addClasspathUrl(URL classpathUrl) {
        classLoader.addURL(classpathUrl);
    }

    private Expression createExpression(String expr) {
        if (isValidImport(expr))
            return new Import(expr);

        if (isValidClass(expr) || isValidInterface(expr))
            return new ClassOrInterface(expr);

        if (isValidAssignmentWithType(expr)) {
            return new AssignmentWithType(expr);
        }

        if (isValidAssignment(expr)) {
            return new Assignment(expr);
        }

        return new Value(expr);
    }

    @multimethod
    private Either<? extends Throwable, Evaluation> evaluate(Expression expression) {
        return new multi() {}.<Either<? extends Throwable, Evaluation>>
                methodOption(expression).getOrElse(evaluateExpression(expression));
    }

    @multimethod
    private Either<? extends Throwable, Evaluation> evaluate(ClassOrInterface expression) {
        if (classLoader.isClassLoaded(expression.type())) {
            return left(new UnsupportedOperationException("Redefining classes not supported"));
        }

        try {
            File outputJavaFile = file(outputDirectory, expression.type() + ".java");

            String sources = renderExpressionClass(context, expression.type(), expression);
            Files.write(sources.getBytes(), outputJavaFile);
            compile(outputJavaFile);
            classLoader.loadClass(expression.type());

            Evaluation evaluation = evaluation(expression.type(), sources, expression, Result.noResult());
            context = context.addEvaluation(evaluation);

            return right(evaluation);
        } catch (Exception e) {
            return left(Utils.unwrapException(e));
        }
    }

    private Either<? extends Throwable, Evaluation> evaluateExpression(Expression expression) {
        String className = randomIdentifier("Evaluation");
        File outputJavaFile = file(outputDirectory, className + ".java");
        File outputClassFile = file(outputDirectory, className + ".class");

        try {
            String sources = renderExpressionClass(context, className, expression);
            Files.write(sources.getBytes(), outputJavaFile);

            compile(outputJavaFile);

            Class<?> expressionClass = classLoader.loadClass(className);
            Object expressionInstance = expressionClass.newInstance();
            expressionClass.getMethod("init", EvaluationContext.class).invoke(expressionInstance, context);
            Object resultObject = expressionClass.getMethod("evaluate").invoke(expressionInstance);

            if (resultObject != null) {
                Evaluation evaluation = evaluation(className, sources, expression, some(Result.result(nextResultKeyFor(expression), resultObject)));
                context = context.addEvaluation(evaluation);
                return right(evaluation);
            } else {
                Evaluation evaluation = evaluation(className, sources, expression, Result.noResult());

                context = context.addEvaluation(evaluation);
                return right(evaluation);
            }
        } catch (Throwable e) {
            return left(Utils.unwrapException(e));
        } finally {
            outputJavaFile.delete();
            outputClassFile.delete();
        }
    }

    private String nextResultKeyFor(Expression expression) {
        return (expression instanceof WithKey)
                ? ((WithKey) expression).key()
                : context.nextResultKey();
    }

    private void compile(File file) throws Exception {
        OutputStream errorStream = new ByteArrayOutputStream();
        String classpath = sequence(System.getProperty("java.class.path"))
                .join(sequence(classLoader.getURLs()).map(toString)).toString(pathSeparator);

        int errorCode = getSystemJavaCompiler().run(null, null, errorStream, "-cp", classpath, file.getCanonicalPath());

        if (errorCode != 0)
            throw new ExpressionCompilationException(errorCode, errorStream.toString());
    }
}
