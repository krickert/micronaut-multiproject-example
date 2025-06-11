#\!/bin/bash

echo "=== YAPPY PROJECT TEST ANALYSIS ==="
echo "Date: $(date)"
echo ""

# Count total modules with tests
modules_with_tests=$(find . -path "*/build/test-results/test/*.xml" -type f  < /dev/null |  grep -v "/binary/" | sed 's|/build/test-results/test/.*||' | sort -u | wc -l)
echo "Total modules with tests: $modules_with_tests"
echo ""

# Find all test result files
all_tests=$(find . -path "*/build/test-results/test/*.xml" -type f | grep -v "/binary/")
total_test_files=$(echo "$all_tests" | wc -l)
echo "Total test result files: $total_test_files"

# Count passing vs failing modules
echo ""
echo "=== MODULE SUMMARY ==="
passing_modules=0
failing_modules=0

for module_dir in $(find . -path "*/build/test-results/test" -type d | sort); do
    module_name=$(echo "$module_dir" | sed 's|./||' | sed 's|/build/test-results/test||')
    test_files=$(find "$module_dir" -name "*.xml" -type f | grep -v "/binary/")
    
    if [ -n "$test_files" ]; then
        failures=$(echo "$test_files" | xargs grep -l 'failures="[1-9]' 2>/dev/null | wc -l)
        errors=$(echo "$test_files" | xargs grep -l 'errors="[1-9]' 2>/dev/null | wc -l)
        total_tests=$(echo "$test_files" | wc -l)
        
        if [ $failures -eq 0 ] && [ $errors -eq 0 ]; then
            echo "✓ $module_name: All tests passed ($total_tests test files)"
            ((passing_modules++))
        else
            echo "✗ $module_name: Has failures/errors ($failures failures, $errors errors out of $total_tests test files)"
            ((failing_modules++))
        fi
    fi
done

echo ""
echo "Summary: $passing_modules modules passing, $failing_modules modules with failures"

echo ""
echo "=== FAILURE CATEGORIES ==="

# Categorize failures
echo ""
echo "1. Port Binding Failures (Address already in use):"
find . -path "*/build/test-results/test/*.xml" -type f -exec grep -l "Address already in use" {} \; | while read file; do
    echo "   - $(basename $file .xml) in $(echo $file | sed 's|./||' | sed 's|/build/test-results/test/.*||')"
done

echo ""
echo "2. Connection Failures:"
find . -path "*/build/test-results/test/*.xml" -type f -exec grep -l "Connection refused\|ConnectException" {} \; | while read file; do
    echo "   - $(basename $file .xml) in $(echo $file | sed 's|./||' | sed 's|/build/test-results/test/.*||')"
done

echo ""
echo "3. Timeout Failures:"
find . -path "*/build/test-results/test/*.xml" -type f -exec grep -l "TimeoutException\|timed out" {} \; | while read file; do
    echo "   - $(basename $file .xml) in $(echo $file | sed 's|./||' | sed 's|/build/test-results/test/.*||')"
done

echo ""
echo "4. Container/Docker Related Failures:"
find . -path "*/build/test-results/test/*.xml" -type f -exec grep -l "container\|docker\|testcontainers" {} \; | while read file; do
    if grep -q 'failures="[1-9]' "$file" 2>/dev/null; then
        echo "   - $(basename $file .xml) in $(echo $file | sed 's|./||' | sed 's|/build/test-results/test/.*||')"
    fi
done

echo ""
echo "=== CRITICAL FAILURES REQUIRING IMMEDIATE ATTENTION ==="

# Find all failing tests and extract key information
find . -path "*/build/test-results/test/*.xml" -type f | while read file; do
    if grep -q 'failures="[1-9]' "$file" 2>/dev/null || grep -q 'errors="[1-9]' "$file" 2>/dev/null; then
        module=$(echo $file | sed 's|./||' | sed 's|/build/test-results/test/.*||')
        testname=$(basename $file .xml | sed 's/TEST-//')
        
        # Extract failure message
        failure_msg=$(grep -A1 '<failure' "$file" | grep 'message=' | sed 's/.*message="\([^"]*\)".*/\1/' | head -1)
        
        if [ -n "$failure_msg" ]; then
            echo ""
            echo "Module: $module"
            echo "Test: $testname"
            echo "Failure: $failure_msg"
        fi
    fi
done | head -20

