# Build all modules
build-all:
    ./mvnw clean install

# Test all modules
test-all:
    ./mvnw clean verify

# Clean all modules
clean:
    ./mvnw clean

# Build github-pulse
build-pulse:
    ./mvnw -pl github-pulse -am clean install -DskipTests

# Build monday-report
build-monday:
    ./mvnw -pl monday-report -am clean install -DskipTests

# Build working-group-reporting
build-wg:
    ./mvnw -pl working-group-reporting/cli-app,working-group-reporting/detection-app -am clean install -DskipTests
