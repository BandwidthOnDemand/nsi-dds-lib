package net.es.nsi.dds.lib.test;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.Test;
import static org.junit.Assert.*;

/** Checks password lifetime without creating stores or changing JVM security state. */
public class KeytoolFixtureTest {
    @Test
    public void passwordIsStableWithinEachFixtureLifetimeAndFreshAcrossLifetimes() throws Exception {
        URL fixtureLocation = KeytoolFixture.class.getProtectionDomain().getCodeSource().getLocation();
        // Exclude the application classloader so each fixture initializes independently.
        try (URLClassLoader firstLoader = new URLClassLoader(
                new URL[]{fixtureLocation}, ClassLoader.getPlatformClassLoader());
                URLClassLoader secondLoader = new URLClassLoader(
                        new URL[]{fixtureLocation}, ClassLoader.getPlatformClassLoader())) {
            Method firstAccessor = Class.forName(KeytoolFixture.class.getName(), true, firstLoader)
                    .getMethod("password");
            Method secondAccessor = Class.forName(KeytoolFixture.class.getName(), true, secondLoader)
                    .getMethod("password");
            String firstPassword = (String) firstAccessor.invoke(null);
            String secondPassword = (String) secondAccessor.invoke(null);

            // Boolean assertions avoid printing generated passwords on failure.
            assertTrue("The first fixture password must remain stable",
                    firstPassword.equals(firstAccessor.invoke(null)));
            assertTrue("The second fixture password must remain stable",
                    secondPassword.equals(secondAccessor.invoke(null)));
            assertFalse("Fresh fixture lifetimes must not reuse a fixed password",
                    firstPassword.equals(secondPassword));
        }
    }
}
