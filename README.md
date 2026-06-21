# PowerShell Shell

This project is a custom shell built in PowerShell. The goal was to understand how command-line interpreters work by implementing shell features from scratch instead of relying on the default PowerShell behavior.

## What I Implemented

### Basic Shell Functionality
- Created an interactive shell prompt.
- Accepted user commands continuously until exit.
- Parsed commands and executed them.

### Command Navigation
- Implemented directory navigation commands.
- Supported moving between folders and working with relative and absolute paths.
- Added functionality to display the current working directory.

### Quote Handling
- Implemented support for quoted strings.
- Allowed arguments containing spaces to be treated as a single argument.
- Handled both single and double quotes correctly.

### Input/Output Redirection
- Added support for output redirection (`>`).
- Added support for append redirection (`>>`).
- Added support for input redirection (`<`).
- Enabled reading from and writing to files through shell commands.

### Pipelines
- Implemented command pipelines using (`|`).
- Allowed the output of one command to be used as the input of another command.
- Supported chaining multiple commands together.

### Background Jobs
- Implemented background execution using (`&`).
- Allowed commands to run without blocking the shell.
- Enabled users to continue entering commands while background tasks were running.

## Key Concepts Learned

Through this project, I learned:
- Command parsing
- Process creation and management
- File I/O operations
- Stream handling
- Shell architecture
- Inter-process communication through pipelines
- Background job execution

## Example Commands

```powershell
cd Documents

echo "Hello World"

dir | findstr ".txt"

echo "Log Entry" >> log.txt

sort < numbers.txt

ping google.com &
