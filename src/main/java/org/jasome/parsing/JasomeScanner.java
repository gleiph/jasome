package org.jasome.parsing;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jasome.calculators.*;
import org.jasome.SomeClass;
import org.jasome.SomeMethod;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JasomeScanner {
    private Set<PackageMetricCalculator> packageCalculators;
    private Set<ClassMetricCalculator> classCalculators;
    private Set<MethodMetricCalculator> methodCalculators;

    public JasomeScanner() {
        packageCalculators = new HashSet<PackageMetricCalculator>();
        classCalculators = new HashSet<ClassMetricCalculator>();
        methodCalculators = new HashSet<MethodMetricCalculator>();
    }

    public void registerPackageCalculator(PackageMetricCalculator calculator) {
        packageCalculators.add(calculator);
    }

    public void registerClassCalculator(ClassMetricCalculator calculator) {
        classCalculators.add(calculator);
    }

    public void registerMethodCalculator(MethodMetricCalculator calculator) {
        methodCalculators.add(calculator);
    }

    public void scan(Collection<File> sourceFiles) throws IOException {

        Map<String, List<Pair<ClassOrInterfaceDeclaration, SourceContext>>> packages = gatherPackages(sourceFiles);

        JasomeOutputTree output = new JasomeOutputTree();

        for(Map.Entry<String, List<Pair<ClassOrInterfaceDeclaration, SourceContext>>> entry: packages.entrySet()) {
            String packageName = entry.getKey();


            { //First, get the package metrics
                SourceContext packageContext = new SourceContext();
                packageContext.setPackageName(packageName);

                for (PackageMetricCalculator packageMetricCalculator : packageCalculators) {
                    List<ClassOrInterfaceDeclaration> classes = entry.getValue().stream().map(Pair::getKey).collect(Collectors.toList());
                    Set<Calculation> calculations = packageMetricCalculator.calculate(classes, packageContext);
                    output.addCalculations(calculations, packageName);
                }

            }


            //Now the class metrics
            for(Pair<ClassOrInterfaceDeclaration, SourceContext> classAndContext: entry.getValue()) {
                ClassOrInterfaceDeclaration classDefinition = classAndContext.getLeft();

                String className = classDefinition.getNameAsString();

                if(classDefinition.getParentNode().isPresent()) {
                    Node parentNode  = classDefinition.getParentNode().get();
                    if(parentNode instanceof ClassOrInterfaceDeclaration) {
                        className = ((ClassOrInterfaceDeclaration)parentNode).getNameAsString() + "." +
                                classDefinition.getNameAsString();
                    }
                }

                SourceContext classContext = classAndContext.getRight();

                {
                    for (ClassMetricCalculator classMetricCalculator : classCalculators) {
                        Set<Calculation> calculations = classMetricCalculator.calculate(classDefinition, classContext);
                        output.addCalculations(calculations, packageName, className);
                    }

                }

                //And finally the method metrics
                for(MethodDeclaration methodDeclaration: classDefinition.getMethods()) {
                    SourceContext methodContext = new SourceContext();
                    methodContext.setPackageName(classContext.getPackageName());
                    methodContext.setImports(classContext.getImports());
                    methodContext.setClassDefinition(Optional.of(classDefinition));

                    for (MethodMetricCalculator methodMetricCalculator : methodCalculators) {
                        Set<Calculation> calculations = methodMetricCalculator.calculate(methodDeclaration, methodContext);
                        output.addCalculations(calculations, packageName, className, methodDeclaration.getDeclarationAsString());
                    }

                }

            }
        }

        System.out.println(output);

    }

    private Map<String, List<Pair<ClassOrInterfaceDeclaration, SourceContext>>> gatherPackages(Collection<File> sourceFiles) throws FileNotFoundException {

        Map<String, List<Pair<ClassOrInterfaceDeclaration, SourceContext>>> packages = new HashMap<String, List<Pair<ClassOrInterfaceDeclaration, SourceContext>>>();

        for (File sourceFile : sourceFiles) {
            FileInputStream in = new FileInputStream(sourceFile);

            CompilationUnit cu = JavaParser.parse(in);

            String packageName = cu.getPackageDeclaration().map((p) -> p.getName().asString()).orElse("default");
            List<ImportDeclaration> imports = cu.getImports();

            SourceContext sourceContext = new SourceContext();
            sourceContext.setPackageName(packageName);
            sourceContext.setImports(imports);

            List<ClassOrInterfaceDeclaration> classes = cu.getNodesByType(ClassOrInterfaceDeclaration.class);

            if (!packages.containsKey(packageName)) {
                packages.put(packageName, new ArrayList<Pair<ClassOrInterfaceDeclaration, SourceContext>>());
            }

            for (ClassOrInterfaceDeclaration clazz : classes) {
                packages.get(packageName).add(Pair.of(clazz, sourceContext));
            }
        }

        return packages;
    }

}

//TODO: this is awful, write some tests against it and refactor heavily
class JasomeOutputTree {
    private Node root;

    public JasomeOutputTree() {
        root = new Node();
        root.name = "root";
        //root.sourceContext = SourceContext.NONE;
    }

    public String toString() {
        return root.toString(0);
    }

    public void addCalculations(Set<Calculation> metrics, String... navigation) {
        synchronized (root) {
            addCalculations(metrics, root, navigation);
        }
    }

    private void addCalculations(Set<Calculation> metrics, Node root, String... navigation) {

        Optional<Node> foundNodeOpt = root.children.stream().filter(c->c.name.equals(navigation[0])).findFirst();

        Node correctNode;
        if(!foundNodeOpt.isPresent()) {
            correctNode = new Node();
            correctNode.name = navigation[0];
            root.children.add(correctNode);
        } else {
            correctNode = foundNodeOpt.get();
        }

        if(navigation.length == 1) { //at the end
            correctNode.metrics.addAll(metrics);
        } else {
            addCalculations(metrics, correctNode, Arrays.copyOfRange(navigation, 1, navigation.length));
        }
    }


    private static class Node {
        private String name;
        //private SourceContext sourceContext;
        private Set<Node> children = new HashSet<Node>();
        private Set<Calculation> metrics = new HashSet<Calculation>();

        public String toString(int level) {
            StringBuilder sb = new StringBuilder();
            sb.append(StringUtils.repeat(' ', level));
            sb.append(name);
            sb.append("\n");
            //do all metrics
            List<Calculation> sortedMetrics = metrics.stream().sorted(new Comparator<Calculation>() {
                @Override
                public int compare(Calculation o1, Calculation o2) {
                    return o1.getName().compareTo(o2.getName());
                }
            }).collect(Collectors.toList());

            for(Calculation metric: sortedMetrics) {
                sb.append(StringUtils.repeat(' ', level)+"+");
                sb.append(metric.getName()+": "+metric.getValue());
                sb.append("\n");
            }
            for(Node child: children) {
                sb.append(child.toString(level+1));
            }
            return sb.toString();
        }
    }
}