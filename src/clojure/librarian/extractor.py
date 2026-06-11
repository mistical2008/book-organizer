import sys
import os
import zipfile
import re

try:
    from bs4 import BeautifulSoup
    HAS_BS4 = True
except ImportError:
    HAS_BS4 = False

def clean_xml_tags(text):
    # Fallback XML tag stripper
    return re.sub(r'<[^>]+>', ' ', text)

def extract_epub(filepath):
    text_content = []
    try:
        with zipfile.ZipFile(filepath, 'r') as z:
            file_list = z.namelist()
            html_files = [f for f in file_list if f.lower().endswith(('.xhtml', '.html', '.htm'))]
            html_files.sort()
            total_chars = 0
            for name in html_files:
                if total_chars > 250000:
                    break
                try:
                    with z.open(name) as f:
                        raw_data = f.read().decode('utf-8', errors='ignore')
                        if HAS_BS4:
                            soup = BeautifulSoup(raw_data, 'html.parser')
                            for script in soup(["script", "style"]):
                                script.decompose()
                            text = soup.get_text()
                        else:
                            text = clean_xml_tags(raw_data)
                        
                        lines = (line.strip() for line in text.splitlines())
                        chunks = (phrase.strip() for line in lines for phrase in line.split("  "))
                        clean_text = '\n'.join(chunk for chunk in chunks if chunk)
                        text_content.append(clean_text)
                        total_chars += len(clean_text)
                except Exception as e:
                    pass
    except Exception as e:
        print(f"Error reading epub: {e}", file=sys.stderr)
    return "\n".join(text_content)

def extract_fb2(filepath):
    try:
        with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
            content = f.read()
        
        if HAS_BS4:
            soup = BeautifulSoup(content, 'html.parser')
            text = soup.get_text()
        else:
            text = clean_xml_tags(content)
            
        lines = (line.strip() for line in text.splitlines())
        chunks = (phrase.strip() for line in lines for phrase in line.split("  "))
        return '\n'.join(chunk for chunk in chunks if chunk)
    except Exception as e:
        print(f"Error reading fb2: {e}", file=sys.stderr)
        return ""

def main():
    if len(sys.argv) < 2:
        print("Usage: python extractor.py <file_path>")
        sys.exit(1)
        
    filepath = sys.argv[1]
    ext = os.path.splitext(filepath)[1].lower()
    
    if ext == '.epub':
        print(extract_epub(filepath))
    elif ext == '.fb2':
        print(extract_fb2(filepath))
    else:
        print(f"Unsupported file type: {ext}", file=sys.stderr)
        sys.exit(1)

if __name__ == '__main__':
    main()
