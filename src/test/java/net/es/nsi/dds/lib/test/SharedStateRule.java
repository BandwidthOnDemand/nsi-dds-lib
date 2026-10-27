package net.es.nsi.dds.lib.test;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * Serializes cooperating tests on a shared JVM lock and restores selected
 * properties, including previously absent values. Tests remain responsible for
 * restoring other state such as providers, the default time zone, and SSL contexts.
 */
public final class SharedStateRule implements TestRule {
    private static final Object GLOBAL_STATE = new Object();
    private final String[] propertyNames;

    /**
     * Selects properties to restore around each cooperating test.
     *
     * @param propertyNames system-property names; an empty array provides locking only
     */
    public SharedStateRule(String... propertyNames) {
        this.propertyNames = propertyNames.clone();
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                synchronized (GLOBAL_STATE) {
                    Map<String, String> previous = new LinkedHashMap<>();
                    for (String name : propertyNames) {
                        previous.put(name, System.getProperty(name));
                    }
                    try {
                        base.evaluate();
                    } finally {
                        previous.forEach((name, value) -> {
                            if (value == null) {
                                System.clearProperty(name);
                            } else {
                                System.setProperty(name, value);
                            }
                        });
                    }
                }
            }
        };
    }
}
