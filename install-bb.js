import { execSync } from 'child_process';
import fs from 'fs';

try {
  if (!fs.existsSync('./bb')) {
    console.log('📥 Babashka binary not found locally. Triggering user-space installation...');
    // We run the babashka installation script, placing the binary in the project root (dir = .)
    execSync('curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install', { stdio: 'inherit' });
    execSync('chmod +x install', { stdio: 'inherit' });
    execSync('./install --dir .', { stdio: 'inherit' });
    
    // Clean up temporary install file
    if (fs.existsSync('./install')) {
      fs.unlinkSync('./install');
    }
    
    if (fs.existsSync('./bb')) {
      console.log('✅ Babashka successfully installed locally as `./bb`.');
    } else {
      console.error('❌ Babashka file was not found after installation step.');
    }
  } else {
    console.log('🚀 Babashka binary already present at `./bb`.');
  }
} catch (err) {
  console.error('❌ Failed to install Babashka:');
  console.error(err);
}
