# OSV-Scanner (https://google.github.io/osv-scanner/) checks every resolved Maven dependency.
OSV ?= osv-scanner

.PHONY: setup format lint test demo burst bench audit ci

# MVNLOCAL (set by the author's repoenv helper) keeps the Maven repository inside the repo; empty in CI.
MVN = mvn -B -q $(MVNLOCAL)
DEMO_ARGS = burst --sale demo --units 1000 --buyers 20000 --concurrency 256 --rate 5000 --burst 1000 --timeout-ms 30000

setup:
	$(MVN) -DskipTests dependency:resolve

format:
	$(MVN) spotless:apply

# Formatting (palantir-java-format via Spotless), then compile with every javac warning as an error.
lint:
	$(MVN) spotless:check
	$(MVN) -DskipTests test-compile

# 70 tests against the in-process jedis-mock server; with VALKEY_URL set, the Lua, oversell and
# burst tests also run against that real Valkey (CI does this with a Valkey 9.0 service container).
test:
	$(MVN) verify

# 20,000 HTTP buyers for 1,000 units through the gateway, on jedis-mock (no Valkey needed).
demo:
	$(MVN) -DskipTests test-compile exec:java -Dexec.classpathScope=test \
	  -Dexec.mainClass=portfolio.flashsaleengine.EmbeddedDemo -Dexec.args="$(DEMO_ARGS)"

# The same burst against a real Valkey at VALKEY_URL (default redis://127.0.0.1:6379).
burst:
	$(MVN) -DskipTests compile exec:java -Dexec.mainClass=portfolio.flashsaleengine.Main \
	  -Dexec.args="burst --sale burst --units 1000 --buyers 20000 --concurrency 256 --rate 5000 --burst 1000"

bench:
	@echo "M3: k6 burst of 50k virtual users with peak throughput, oversold units and database load"

# Known vulnerabilities in every resolved Maven dependency, test scope included.
audit:
	mvn -B -q org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom -DoutputFormat=json -DoutputName=bom -DincludeTestScope=true
	$(OSV) scan source -L target/bom.json

ci: setup lint test
