const fs = require('fs');
const content = fs.readFileSync('/app/applet/src/clojure/librarian/core.clj', 'utf8');

let stack = [];
let lines = content.split('\n');

for (let i = 0; i < lines.length; i++) {
  let line = lines[i];
  for (let j = 0; j < line.length; j++) {
    let char = line[j];
    if (char === '(' || char === '[' || char === '{') {
      stack.push({ char, line: i + 1, col: j + 1 });
    } else if (char === ')' || char === ']' || char === '}') {
      if (stack.length === 0) {
        console.log(`Extra close delimiter ${char} at line ${i + 1}, col ${j + 1}`);
      } else {
        let last = stack.pop();
        if ((char === ')' && last.char !== '(') ||
            (char === ']' && last.char !== '[') ||
            (char === '}' && last.char !== '{')) {
          console.log(`Mismatched delimiter ${char} at line ${i + 1}, col ${j + 1} matching ${last.char} from line ${last.line}, col ${last.col}`);
        }
      }
    }
  }
}

if (stack.length > 0) {
  console.log(`Unclosed delimiters:`);
  for (let item of stack) {
    if (item.line >= 860 && item.line <= 960) {
      console.log(`  ${item.char} at line ${item.line}, col ${item.col}`);
    }
  }
} else {
  console.log(`All delimiters perfectly balanced!`);
}
