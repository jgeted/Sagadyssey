import zipfile
import os
import xml.etree.ElementTree as ET

# Path to the .docx file
docx_path = r"C:\Users\jgeted\Desktop\一个想法\一个想法.docx"
output_path = r"C:\Users\jgeted\Desktop\一个想法\extracted_text.txt"

namespaces = {
    'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main',
    'r': 'http://schemas.openxmlformats.org/officeDocument/2006/relationships',
}

def extract_text_from_docx(filepath):
    """Extract all text from a .docx file."""
    text_parts = []

    with zipfile.ZipFile(filepath, 'r') as docx_zip:
        # List all files in the archive for debugging
        file_list = docx_zip.namelist()

        # The main document content is in word/document.xml
        if 'word/document.xml' in file_list:
            xml_content = docx_zip.read('word/document.xml')
            root = ET.fromstring(xml_content)

            # Find all text elements (w:t tags)
            for t_elem in root.iter('{http://schemas.openxmlformats.org/wordprocessingml/2006/main}t'):
                if t_elem.text:
                    text_parts.append(t_elem.text)

            # Add paragraph breaks
            result = []
            for elem in root.iter():
                tag = elem.tag
                if tag.endswith('}p'):
                    # This is a paragraph break
                    result.append('\n')
                elif tag.endswith('}t') and elem.text:
                    result.append(elem.text)

            full_text = ''.join(result).strip()
            return full_text

    return '\n'.join(text_parts)

if __name__ == '__main__':
    if not os.path.exists(docx_path):
        print(f"ERROR: File not found at {docx_path}")
    else:
        text = extract_text_from_docx(docx_path)
        print("=" * 60)
        print("EXTRACTED TEXT:")
        print("=" * 60)
        print(text)
        print("=" * 60)
        print(f"\nTotal characters: {len(text)}")

        # Also save to a text file
        with open(output_path, 'w', encoding='utf-8') as f:
            f.write(text)
        print(f"\nText also saved to: {output_path}")
