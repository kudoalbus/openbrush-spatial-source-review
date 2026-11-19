#!/usr/bin/env python3
"""Generate the source-review Xcode project using included text resources."""
from pathlib import Path
import hashlib

root = Path(__file__).resolve().parents[1]
def key(name):
    return hashlib.sha1(name.encode()).hexdigest()[:24].upper()
def quoted(value):
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'
objects = []
def add(name, body):
    objects.append(f'\t\t{key(name)} = {{ {body} }};')
    return key(name)
def array(values):
    return '(' + ', '.join(values) + ',)'
source_files = sorted(root.glob('Sources/DrawingCore/*.swift')) + sorted(root.glob('App/*.swift'))
source_refs, source_builds = [], []
for file in source_files:
    relative = str(file.relative_to(root))
    ref = add(relative, f'isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = {quoted(relative)}; sourceTree = SOURCE_ROOT;')
    source_refs.append(ref)
    source_builds.append(add(relative + ':build', f'isa = PBXBuildFile; fileRef = {ref};'))
# Review packaging uses only local text resources. Models, textures, atlases and
# artwork from the development repository are deliberately omitted.
fixture = add('fixture', 'isa = PBXFileReference; lastKnownFileType = text.json; path = "ReviewResources/workspace-v3.json"; sourceTree = SOURCE_ROOT;')
fixture_build = add('fixture:build', f'isa = PBXBuildFile; fileRef = {fixture};')
resource_files = [(file.relative_to(root).as_posix(), 'text.json.xcstrings')
                  for file in sorted((root / 'App/Resources').glob('*.xcstrings'))]
resource_files += [
    ('ReviewResources/brush-library/catalog.json', 'text.json'),
    ('ReviewResources/brush-geometry.json', 'text.json'),
    ('ReviewResources/brush-rendering.json', 'text.json'),
    ('ReviewResources/palette-targets.json', 'text.json'),
    ('ReviewResources/brush-textures', 'folder'),
    ('ReviewResources/OPENBRUSH-LICENSE', 'text'),
]
resource_refs, resource_builds = [fixture], [fixture_build]
for relative, file_type in resource_files:
    ref = add(relative, f'isa = PBXFileReference; lastKnownFileType = {file_type}; path = {quoted(relative)}; sourceTree = SOURCE_ROOT;')
    resource_refs.append(ref)
    resource_builds.append(add(relative + ':build', f'isa = PBXBuildFile; fileRef = {ref};'))
signing = add('signing', 'isa = PBXFileReference; lastKnownFileType = text.xcconfig; path = Signing.xcconfig; sourceTree = SOURCE_ROOT;')
product = add('product', 'isa = PBXFileReference; explicitFileType = wrapper.application; path = OpenBrushSpatial.app; sourceTree = BUILT_PRODUCTS_DIR;')
products = add('products', f'isa = PBXGroup; children = {array([product])}; name = Products; sourceTree = "<group>";')
main = add('main', f'isa = PBXGroup; children = {array(source_refs + resource_refs + [signing, products])}; sourceTree = "<group>";')
sources = add('sources', f'isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = {array(source_builds)}; runOnlyForDeploymentPostprocessing = 0;')
resources = add('resources', f'isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = {array(resource_builds)}; runOnlyForDeploymentPostprocessing = 0;')
frameworks = add('frameworks', 'isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;')
project_configs, target_configs = [], []
for config in ['Debug', 'Release']:
    optimization = '-Onone' if config == 'Debug' else '-O'
    project_configs.append(add('project:' + config, f'isa = XCBuildConfiguration; name = {config}; buildSettings = {{ CLANG_ENABLE_MODULES = YES; SWIFT_VERSION = 6.0; SWIFT_OPTIMIZATION_LEVEL = {quoted(optimization)}; DEBUG_INFORMATION_FORMAT = dwarf; }};'))
    settings = {
        'PRODUCT_NAME': 'OpenBrushSpatial', 'PRODUCT_BUNDLE_IDENTIFIER': 'com.kudoalbus.openbrush.spatial',
        'SDKROOT': 'xros', 'SUPPORTED_PLATFORMS': 'xros xrsimulator', 'TARGETED_DEVICE_FAMILY': '7',
        'XROS_DEPLOYMENT_TARGET': '2.0', 'GENERATE_INFOPLIST_FILE': 'YES',
        'INFOPLIST_KEY_NSHandsTrackingUsageDescription': 'Track your hands to draw, erase and recolor in the spatial studio.',
        'INFOPLIST_KEY_NSMicrophoneUsageDescription': 'Use microphone sound levels to animate sound-reactive brushes in the spatial studio.',
        'INFOPLIST_KEY_NSAccessoryTrackingUsageDescription': 'Track your spatial stylus to draw and erase in the spatial studio.',
        'INFOPLIST_KEY_CFBundleDisplayName': 'OpenBrush Spatial',
        'INFOPLIST_KEY_UIApplicationSceneManifest_Generation': 'YES',
        'INFOPLIST_KEY_UIApplicationSupportsIndirectInputEvents': 'YES',
        'INFOPLIST_KEY_UILaunchScreen_Generation': 'YES',
        'INFOPLIST_KEY_LSSupportsOpeningDocumentsInPlace': 'YES',
        'INFOPLIST_KEY_UIFileSharingEnabled': 'YES',
        'CURRENT_PROJECT_VERSION': '5', 'MARKETING_VERSION': '0.1.4',
        'CODE_SIGN_STYLE': 'Automatic', 'SWIFT_STRICT_CONCURRENCY': 'complete',
        'LD_RUNPATH_SEARCH_PATHS': '$(inherited) @executable_path/Frameworks',
        'ENABLE_USER_SCRIPT_SANDBOXING': 'YES',
    }
    if config == 'Debug': settings['SWIFT_ACTIVE_COMPILATION_CONDITIONS'] = 'DEBUG $(inherited)'
    body = ' '.join(f'{k} = {quoted(v)};' for k, v in settings.items())
    target_configs.append(add('target:' + config, f'isa = XCBuildConfiguration; name = {config}; baseConfigurationReference = {signing}; buildSettings = {{ {body} }};'))
project_list = add('project-configs', f'isa = XCConfigurationList; buildConfigurations = {array(project_configs)}; defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;')
target_list = add('target-configs', f'isa = XCConfigurationList; buildConfigurations = {array(target_configs)}; defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;')
target = add('target', f'isa = PBXNativeTarget; buildConfigurationList = {target_list}; buildPhases = {array([sources, frameworks, resources])}; buildRules = (); dependencies = (); name = OpenBrushSpatial; productName = OpenBrushSpatial; productReference = {product}; productType = "com.apple.product-type.application";')
project = add('project', f'isa = PBXProject; attributes = {{ BuildIndependentTargetsInParallel = YES; LastUpgradeCheck = 2700; }}; buildConfigurationList = {project_list}; compatibilityVersion = "Xcode 14.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en, Base, "zh-Hans", "zh-Hant"); mainGroup = {main}; productRefGroup = {products}; projectDirPath = ""; projectRoot = ""; targets = {array([target])};')
folder = root / 'OpenBrushSpatial.xcodeproj'
folder.mkdir(exist_ok=True)
(folder / 'project.pbxproj').write_text('// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tclasses = {};\n\tobjectVersion = 56;\n\tobjects = {\n' + '\n'.join(objects) + f'\n\t}};\n\trootObject = {project};\n}}\n')
scheme = folder / 'xcshareddata/xcschemes/OpenBrushSpatial.xcscheme'
scheme.parent.mkdir(parents=True, exist_ok=True)
reference = f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="OpenBrushSpatial.app" BlueprintName="OpenBrushSpatial" ReferencedContainer="container:OpenBrushSpatial.xcodeproj"/>'
scheme.write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2700" version="1.3">
  <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{reference}</BuildActionEntry></BuildActionEntries></BuildAction>
  <TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.DebuggerFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"/>
  <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.DebuggerFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{reference}</BuildableProductRunnable></LaunchAction>
  <ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{reference}</BuildableProductRunnable></ProfileAction>
  <AnalyzeAction buildConfiguration="Debug"/>
  <ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
''')
print(f'Generated {folder.name} with {len(source_files)} Swift files')
