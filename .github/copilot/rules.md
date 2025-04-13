# Coding Rules for copilots

## Code Structure

1. **Flat Directory Structure**: Maintain a flat code structure without unnecessary directories.
   - All source code should be placed directly under the `source` directory
   - All test code should be placed directly under the `test` directory
   - Do not create subdirectories for main package
   - For subpackages within the main package, subdirectories are optional - you can create them if needed for organization, but it's not required

2. **Resources**: Place resources directly in the source directory alongside code files, not in a separate resources directory.