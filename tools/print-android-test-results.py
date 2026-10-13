"""Keep Android test outcomes visible when GitHub artifact uploads are unavailable."""
from pathlib import Path
import xml.etree.ElementTree as ET

for path in Path('core/organizer/build').glob('**/TEST-*.xml'):
    root = ET.parse(path).getroot()
    for element in root.iter():
        element.tag = element.tag.split("}")[-1]
    print(f"{path}: {root.attrib.get('tests')} tests, {root.attrib.get('failures', '0')} failures, {root.attrib.get('errors', '0')} errors")
    for case in root.iter('testcase'):
        for kind in ('failure', 'error'):
            issue = case.find(kind)
            if issue is not None:
                print(f"{case.attrib.get('classname')}.{case.attrib.get('name')}: {issue.attrib.get('message')}\n{issue.text or ''}")
