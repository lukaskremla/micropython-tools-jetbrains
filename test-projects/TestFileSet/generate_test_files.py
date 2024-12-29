"""
This is an AI generated script. When run it will generate a wide set of different files for testing purposes.
It will generate around 61 files with a total size of around 1.27MB.
Contents of the generated files will be random each time.
The file types include .py files, web files including gzipped ones and some more.
The files will contain difficult characters to serve as test microcontroller File System content.
The intended use case is testing the upload functionality itself
as well as the hashing scripts for their speed and other criteria.
The output of this script will be saved to "test_file_set" in the project root.
Its contents should be uploaded to the test microcontroller.

The web files contain odd characters to test proper encoding.

This code is messy and I haven't manually reviewed it in its entirety,
but it should be sufficient to generate random test file sets for this plugin's needs.

The lorem library is required, make sure to run "pip install lorem" the first time you are running this script.
"test_file_set" directory will be wiped and freshly generated each time!
"""

import gzip
import os
import random
import shutil
import string
from pathlib import Path

import lorem


def generate_random_string(length=10, include_special=False):
    chars = string.ascii_letters + string.digits
    if include_special:
        chars += string.punctuation
    return ''.join(random.choice(chars) for _ in range(length))


def generate_unicode_string(length=10):
    unicode_ranges = [
        (0x0030, 0x0039), (0x0041, 0x005A), (0x0061, 0x007A),
        (0x00A1, 0x00FF), (0x0100, 0x017F), (0x0391, 0x03C9),
        (0x0400, 0x04FF), (0x3040, 0x309F), (0x4E00, 0x4FFF)
    ]
    return ''.join(chr(random.randint(*random.choice(unicode_ranges))) for _ in range(length))


def generate_random_path():
    segments = [generate_random_string(random.randint(3, 8)) for _ in range(random.randint(2, 5))]
    return '/'.join(segments)


def generate_random_url():
    protocols = ['http', 'https', 'ftp', 'sftp', 'ws', 'wss']
    tlds = ['.com', '.org', '.net', '.io', '.dev', '.test']
    return f"{random.choice(protocols)}://{generate_random_string(10)}{random.choice(tlds)}/{generate_random_path()}"


def create_large_text_content(size_target):
    paragraphs = []
    current_size = 0
    while current_size < size_target:
        new_paragraph = lorem.paragraph() + "\n\n"
        paragraphs.append(new_paragraph)
        current_size += len(new_paragraph.encode('utf-8'))
    return ''.join(paragraphs)


def create_js_content(size_target):
    js_content = []

    # Base structure
    js_content.extend([
        '// Advanced JavaScript test file with Unicode and complex patterns',
        'const CONFIG = {',
        f'    apiUrl: "{generate_random_url()}",',
        f'    secretKey: "{generate_random_string(32)}",',
        '    maxRetries: 5,',
        '    timeout: 30000',
        '};',
        '',
        'class DataProcessor {',
        '    constructor() {',
        f'        this.id = "{generate_unicode_string()}";',  # Changed from Symbol
        '        this.cache = {};',  # Changed from Map
        '        this.initialize();',
        '    }',
        ''
    ])

    # Add methods and data until reaching target size
    while len('\n'.join(js_content).encode('utf-8')) < size_target:
        method_name = f"process{generate_random_string(8)}"
        js_content.extend([
            f'    {method_name}() {{',
            f'        const data = {{"id": "{generate_random_string()}", "value": {random.random()}}};',
            '        this.cache[data.id] = data;',  # Changed from Map.set
            f'        console.log("Processing data:", data);',  # Removed JSON.stringify
            '        return data;',
            '    }',
            ''
        ])

    js_content.extend([
        '}',
        '',
        'const processor = new DataProcessor();',
        'processor.initialize();'
    ])

    return '\n'.join(js_content)


def create_css_content(size_target):
    css_content = []
    colors = [f"#{generate_random_string(6, False)}" for _ in range(10)]

    while len('\n'.join(css_content).encode('utf-8')) < size_target:
        class_name = f"component-{generate_random_string(8)}"
        css_content.extend([
            f'.{class_name} {{',
            f'    color: {random.choice(colors)};',
            f'    background: linear-gradient(45deg, {random.choice(colors)}, {random.choice(colors)});',
            f'    font-family: "{generate_unicode_string(5)}", sans-serif;',
            f'    padding: {random.randint(5, 50)}px;',
            f'    margin: {random.randint(5, 50)}px;',
            f'    transform: rotate({random.randint(-180, 180)}deg);',
            '}',
            ''
        ])

    return '\n'.join(css_content)


def create_svg_content(size_target):
    svg_content = ['<?xml version="1.0" encoding="UTF-8"?>',
                   '<svg xmlns="http://www.w3.org/2000/svg" width="800" height="600">']

    while len('\n'.join(svg_content).encode('utf-8')) < size_target:
        # Add random shapes
        shape_type = random.choice(['rect', 'circle', 'path'])
        if shape_type == 'rect':
            svg_content.append(f'    <rect x="{random.randint(0, 700)}" y="{random.randint(0, 500)}" '
                               f'width="{random.randint(10, 100)}" height="{random.randint(10, 100)}" '
                               f'fill="#{generate_random_string(6, False)}"/>')
        elif shape_type == 'circle':
            svg_content.append(f'    <circle cx="{random.randint(0, 800)}" cy="{random.randint(0, 600)}" '
                               f'r="{random.randint(5, 50)}" fill="#{generate_random_string(6, False)}"/>')
        else:
            svg_content.append(f'    <path d="M {random.randint(0, 800)} {random.randint(0, 600)} '
                               f'L {random.randint(0, 800)} {random.randint(0, 600)} Z" '
                               f'fill="#{generate_random_string(6, False)}"/>')

    svg_content.append('</svg>')
    return '\n'.join(svg_content)


def create_html_content(size_target):
    html_content = [
        '<!DOCTYPE html>',
        '<html lang="en">',
        '<head>',
        '    <meta charset="UTF-8">',
        '    <meta name="viewport" content="width=device-width, initial-scale=1.0">',
        f'    <title>{generate_random_string(20)}</title>',
        '</head>',
        '<body>'
    ]

    while len('\n'.join(html_content).encode('utf-8')) < size_target:
        element_type = random.choice(['div', 'p', 'section', 'article'])
        html_content.extend([
            f'    <{element_type} class="{generate_random_string(8)}">',
            f'        {lorem.paragraph()}',
            f'    </{element_type}>'
        ])

    html_content.append('</body></html>')
    return '\n'.join(html_content)


def create_python_content(module_type='standard'):
    classes = random.randint(2, 5)
    methods_per_class = random.randint(3, 7)

    content = [
        'import os',
        'import sys',
        'import random',
        'import datetime',
        'from pathlib import Path',
        'from typing import Dict, List, Optional, Union',
        '',
        f'# {generate_random_string(50)}',
        ''
    ]

    if module_type == 'test':
        content.extend([
            'import unittest',
            'import pytest',
            ''
        ])

    for c in range(classes):
        class_name = f"Test{generate_random_string(8)}" if module_type == 'test' else generate_random_string(8)
        content.extend([
            f'class {class_name}:',
            '    """',
            f'    {lorem.paragraph()}',
            '    """',
            '',
            '    def __init__(self):',
            f'        self.data: Dict[str, Any] = {{"id": "{generate_random_string()}"}}',
            ''
        ])

        for m in range(methods_per_class):
            method_name = f"test_{generate_random_string(8)}" if module_type == 'test' else f"process_{generate_random_string(8)}"
            content.extend([
                f'    def {method_name}(self, param1: str, param2: Optional[int] = None) -> Union[str, int]:',
                '        """',
                f'        {lorem.paragraph()}',
                '        """',
                f'        result = self.data.get(param1, {random.randint(1, 1000)})',
                '        return result if param2 is None else result + param2',
                ''
            ])

    content.extend([
        'def main():',
        f'    instance = {class_name}()',
        f'    print(instance.{method_name}("test", 42))',
        '',
        'if __name__ == "__main__":',
        '    main()',
        ''
    ])

    return '\n'.join(content)


def create_markdown_content(size_target):
    md_content = [
        f'# {generate_random_string(20)}',
        '',
        f'## {generate_random_string(30)}',
        ''
    ]

    while len('\n'.join(md_content).encode('utf-8')) < size_target:
        section_type = random.choice(['paragraph', 'list', 'code', 'quote'])
        if section_type == 'paragraph':
            md_content.extend([lorem.paragraph(), ''])
        elif section_type == 'list':
            md_content.extend(['- ' + lorem.sentence() for _ in range(5)] + [''])
        elif section_type == 'code':
            md_content.extend(['```python', create_python_content(), '```', ''])
        else:
            md_content.extend(['> ' + lorem.paragraph(), ''])

    return '\n'.join(md_content)


def create_test_dataset(base_path):
    base_dir = Path(base_path)

    # Create directory structure
    directories = {
        'python': ['core', 'tests', 'utils', 'handlers', 'modules'],
        'web': ['css', 'js', 'svg', 'html', 'fonts'],
        'docs': ['markdown', 'text', 'old']
    }

    for main_dir, subdirs in directories.items():
        for subdir in subdirs:
            (base_dir / main_dir / subdir).mkdir(parents=True, exist_ok=True)

    total_size = 0
    file_count = 0
    target_total_size = 1.7 * 1024 * 1024  # 1.7MB in bytes

    # Calculate size distribution
    size_per_category = target_total_size / 12  # Divide among different file types

    # Generate Python files (more than before)
    python_dirs = [base_dir / 'python' / d for d in directories['python']]
    for dir_path in python_dirs:
        for i in range(5):  # 5 files per directory
            file_path = dir_path / f'test_{generate_random_string(8)}.py'
            content = create_python_content('test' if 'tests' in str(dir_path) else 'standard')
            file_path.write_text(content)
            total_size += file_path.stat().st_size
            file_count += 1

    # Generate web files
    web_files = {
        'css': (create_css_content, size_per_category / 5),
        'js': (create_js_content, size_per_category / 4),
        'svg': (create_svg_content, size_per_category / 4),
        'html': (create_html_content, size_per_category / 4)
    }

    for file_type, (creator_func, size_target) in web_files.items():
        dir_path = base_dir / 'web' / file_type
        for i in range(3):
            content = creator_func(int(size_target))

            # Normal file
            file_path = dir_path / f'file_{generate_random_string(8)}.{file_type}'
            file_path.write_text(content)
            total_size += file_path.stat().st_size
            file_count += 1

            # Gzipped version
            gz_path = dir_path / f'file_{generate_random_string(8)}.{file_type}.gz'
            with gzip.open(gz_path, 'wt') as f:
                f.write(content)
            total_size += gz_path.stat().st_size
            file_count += 1

    # Generate WOFF files
    fonts_dir = base_dir / 'web' / 'fonts'
    for i in range(2):
        file_path = fonts_dir / f'font_{generate_random_string(8)}.woff'
        content = bytes([random.randint(0, 255) for _ in range(int(size_per_category / 4))])
        file_path.write_bytes(content)
        total_size += file_path.stat().st_size
        file_count += 1

    # Generate .ico file
    ico_path = base_dir / 'web' / 'favicon.ico'
    ico_content = bytes([random.randint(0, 255) for _ in range(1024)])  # 1KB ico file
    ico_path.write_bytes(ico_content)
    total_size += ico_path.stat().st_size
    file_count += 1

    # Generate documentation files
    doc_types = {
        'markdown': (create_markdown_content, '.md'),
        'text': (create_large_text_content, '.txt'),
        'old': (create_large_text_content, '.old')
    }

    for doc_type, (creator_func, extension) in doc_types.items():
        dir_path = base_dir / 'docs' / doc_type
        for i in range(3):
            content = creator_func(int(size_per_category / 3))
            file_path = dir_path / f'doc_{generate_random_string(8)}{extension}'
            file_path.write_text(content)
            total_size += file_path.stat().st_size
            file_count += 1

    return {
        'total_size_bytes': total_size,
        'total_size_mb': total_size / (1024 * 1024),
        'file_count': file_count
    }


file_count = 0
total_size = 0


def scan_file_path(path):
    global file_count, total_size
    with os.scandir(path) as it:
        for entry in it:
            if entry.is_file():
                file_count += 1
                total_size += entry.stat().st_size
            elif entry.is_dir():
                scan_file_path(entry.path)


def main():
    global file_count, total_size
    base_path = Path('test_file_set')
    print("Deleting old data set...")

    shutil.rmtree(base_path)

    print("Creating test dataset...")
    create_test_dataset(base_path)  # Generated stats are inaccurate

    scan_file_path(base_path)  # Separate method for getting proper stats

    print("\nDataset creation completed!")
    print(f"Total files created: {file_count}")
    print(f"Total size: {total_size / (1024 * 1024):.2f} MB")
    print(f"Dataset location: {base_path.absolute()}")


if __name__ == "__main__":
    main()
