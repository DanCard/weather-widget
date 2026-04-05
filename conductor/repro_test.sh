#!/bin/bash
# Reproduction script for staggered-tests improvement

mkdir -p app/build/test-results/testMediumDebugUnitTestFresh
cat <<EOF > app/build/test-results/testMediumDebugUnitTestFresh/TEST-com.weatherwidget.ExampleTest.xml
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.weatherwidget.ExampleTest" tests="2" failures="1" errors="0" skipped="0" time="0.5" timestamp="2026-04-05T10:00:00">
  <testcase name="testSuccess" classname="com.weatherwidget.ExampleTest" time="0.2"/>
  <testcase name="testFailure" classname="com.weatherwidget.ExampleTest" time="0.3">
    <failure message="expected:&lt;1&gt; but was:&lt;2&gt;" type="java.lang.AssertionError">java.lang.AssertionError: expected:&lt;1&gt; but was:&lt;2&gt;</failure>
  </testcase>
</testsuite>
EOF

# Run unit-tests.sh with this fake result
# We need to bypass actual gradle run if possible, or just let it fail and then check reporting.
# Since unit-tests.sh runs gradlew, we might need a fake gradlew too.
cat <<EOF > fake_gradlew
#!/bin/bash
exit 0
EOF
chmod +x fake_gradlew

# Temporarily point to fake_gradlew
export GRADLEW="./fake_gradlew"

echo "Running unit-tests.sh in single-invocation mode with fake results..."
# We need to trick it into thinking it ran the Medium bucket
./scripts/unit-tests.sh --single-invocation Medium
