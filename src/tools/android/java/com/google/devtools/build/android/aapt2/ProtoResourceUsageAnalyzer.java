// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.android.aapt2;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import com.android.build.gradle.tasks.ResourceUsageAnalyzer;
import com.android.resources.ResourceType;
import com.android.tools.lint.checks.ResourceUsageModel;
import com.android.tools.lint.checks.ResourceUsageModel.Resource;
import com.android.tools.lint.detector.api.LintUtils;
import com.android.utils.XmlUtils;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.devtools.build.android.aapt2.ProtoApk.ManifestVisitor;
import com.google.devtools.build.android.aapt2.ProtoApk.ReferenceVisitor;
import com.google.devtools.build.android.aapt2.ProtoApk.ResourcePackageVisitor;
import com.google.devtools.build.android.aapt2.ProtoApk.ResourceValueVisitor;
import com.google.devtools.build.android.aapt2.ProtoApk.ResourceVisitor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Attr;
import org.w3c.dom.DOMException;
import org.w3c.dom.NamedNodeMap;
import org.xml.sax.SAXException;

/** A resource usage analyzer tha functions on apks in protocol buffer format. */
public class ProtoResourceUsageAnalyzer extends ResourceUsageAnalyzer {

  private static final Logger logger = Logger.getLogger(ProtoResourceUsageAnalyzer.class.getName());

  public ProtoResourceUsageAnalyzer(Set<String> resourcePackages, Path mapping, Path logFile)
      throws DOMException, ParserConfigurationException {
    super(resourcePackages, null, null, null, mapping, null, logFile);
  }

  private static Resource parse(ResourceUsageModel model, String resourceTypeAndName) {
    final Iterator<String> iterator = Splitter.on('/').split(resourceTypeAndName).iterator();
    Preconditions.checkArgument(
        iterator.hasNext(), "%s invalid resource name", resourceTypeAndName);
    ResourceType resourceType = ResourceType.getEnum(iterator.next());
    Preconditions.checkArgument(
        iterator.hasNext(), "%s invalid resource name", resourceTypeAndName);
    return model.getResource(resourceType, iterator.next());
  }

  /**
   * Calculate and removes unused resource from the {@link ProtoApk}.
   *
   * @param apk An apk in the aapt2 proto format.
   * @param classes The associated classes for the apk.
   * @param destination Where to write the reduced resources.
   * @param keep A list of resource urls to keep, unused or not.
   * @param discard A list of resource urls to always discard.
   */
  @CheckReturnValue
  public ProtoApk shrink(
      ProtoApk apk,
      Path classes,
      Path destination,
      Collection<String> keep,
      Collection<String> discard)
      throws IOException, ParserConfigurationException, SAXException {

    // record resources and manifest
    apk.visitResources(
        // First, collect all declarations using the declaration visitor.
        // This allows the model to start with a defined set of resources to build the reference
        // graph on.
        apk.visitResources(new ResourceDeclarationVisitor(model())).toUsageVisitor());

    recordClassUsages(classes);

    // Have to give the model xml attributes with keep and discard urls.
    final NamedNodeMap toolAttributes =
        XmlUtils.parseDocument(
                String.format(
                    "<resources xmlns:tools='http://schemas.android.com/tools' tools:keep='%s'"
                        + " tools:discard='%s'></resources>",
                    keep.stream().collect(joining(",")), discard.stream().collect(joining(","))),
                true)
            .getDocumentElement()
            .getAttributes();

    for (int i = 0; i < toolAttributes.getLength(); i++) {
      model().recordToolsAttributes((Attr) toolAttributes.item(i));
    }
    model().processToolsAttributes();

    keepPossiblyReferencedResources();

    final List<Resource> resources = model().getResources();

    List<Resource> roots =
        resources.stream().filter(r -> r.isKeep() || r.isReachable()).collect(toList());

    final Set<Resource> reachable = findReachableResources(roots);
    return apk.copy(
        destination,
        (resourceType, name) -> reachable.contains(model().getResource(resourceType, name)));
  }

  private Set<Resource> findReachableResources(List<Resource> roots) {
    final Multimap<Resource, Resource> referenceLog = HashMultimap.create();
    Deque<Resource> queue = new ArrayDeque<>(roots);
    final Set<Resource> reachable = new HashSet<>();
    while (!queue.isEmpty()) {
      Resource resource = queue.pop();
      if (resource.references != null) {
        resource.references.forEach(
            r -> {
              referenceLog.put(r, resource);
              // add if it has not been marked reachable, therefore processed.
              if (!reachable.contains(r)) {
                queue.add(r);
              }
            });
      }
      // if we see it, it is reachable.
      reachable.add(resource);
    }

    // dump resource reference map:
    final StringBuilder keptResourceLog = new StringBuilder();
    referenceLog
        .asMap()
        .forEach(
            (resource, referencesTo) ->
                keptResourceLog
                    .append(printResource(resource))
                    .append(" => [")
                    .append(
                        referencesTo
                            .stream()
                            .map(ProtoResourceUsageAnalyzer::printResource)
                            .collect(joining(", ")))
                    .append("]\n"));

    logger.fine("Kept resource references:\n" + keptResourceLog);

    return reachable;
  }

  private static String printResource(Resource res) {
    return String.format(
        "{%s[isRoot: %s] = %s}",
        res.getUrl(), res.isReachable() || res.isKeep(), "0x" + Integer.toHexString(res.value));
  }

  private static final class ResourceDeclarationVisitor implements ResourceVisitor {

    private final ResourceShrinkerUsageModel model;
    private final Set<Integer> packageIds = new HashSet<>();

    private ResourceDeclarationVisitor(ResourceShrinkerUsageModel model) {
      this.model = model;
    }

    @Nullable
    @Override
    public ManifestVisitor enteringManifest() {
      return null;
    }

    @Override
    public ResourcePackageVisitor enteringPackage(int pkgId, String packageName) {
      packageIds.add(pkgId);
      return (typeId, resourceType) ->
          (name, resourceId) -> {
            String hexId =
                String.format(
                    "0x%s", Integer.toHexString(((pkgId << 24) | (typeId << 16) | resourceId)));
            model.addDeclaredResource(resourceType, LintUtils.getFieldName(name), hexId, true);
            // Skip visiting the definition when collecting declarations.
            return null;
          };
    }

    ResourceUsageVisitor toUsageVisitor() {
      return new ResourceUsageVisitor(model, ImmutableSet.copyOf(packageIds));
    }
  }

  private static final class ResourceUsageVisitor implements ResourceVisitor {

    private final ResourceShrinkerUsageModel model;
    private final ImmutableSet<Integer> packageIds;

    private ResourceUsageVisitor(
        ResourceShrinkerUsageModel model, ImmutableSet<Integer> packageIds) {
      this.model = model;
      this.packageIds = packageIds;
    }

    @Override
    public ManifestVisitor enteringManifest() {
      return new ManifestVisitor() {
        @Override
        public void accept(String name) {
          ResourceUsageModel.markReachable(model.getResourceFromUrl(name));
        }

        @Override
        public void accept(int value) {
          ResourceUsageModel.markReachable(model.getResource(value));
        }
      };
    }

    @Override
    public ResourcePackageVisitor enteringPackage(int pkgId, String packageName) {
      return (typeId, resourceType) ->
          (name, resourceId) ->
              new ResourceUsageValueVisitor(
                  model, model.getResource(resourceType, name), packageIds);
    }
  }

  private static final class ResourceUsageValueVisitor implements ResourceValueVisitor {

    private final ResourceUsageModel model;
    private final Resource declaredResource;
    private final ImmutableSet<Integer> packageIds;

    private ResourceUsageValueVisitor(
        ResourceUsageModel model, Resource declaredResource, ImmutableSet<Integer> packageIds) {
      this.model = model;
      this.declaredResource = declaredResource;
      this.packageIds = packageIds;
    }

    @Override
    public ReferenceVisitor entering(Path path) {
      return this;
    }

    @Override
    public void acceptOpaqueFileType(Path path) {
      try {
        String pathString = path.toString();
        if (pathString.endsWith(".js")) {
          model.tokenizeJs(
              declaredResource,
              new String(java.nio.file.Files.readAllBytes(path), StandardCharsets.UTF_8));
        } else if (pathString.endsWith(".css")) {
          model.tokenizeCss(
              declaredResource,
              new String(java.nio.file.Files.readAllBytes(path), StandardCharsets.UTF_8));
        } else if (pathString.endsWith(".html")) {
          model.tokenizeHtml(
              declaredResource,
              new String(java.nio.file.Files.readAllBytes(path), StandardCharsets.UTF_8));
        } else {
          // Path is a reference to the apk zip -- unpack it before getting a file reference.
          model.tokenizeUnknownBinary(
              declaredResource,
              java.nio.file.Files.copy(
                      path,
                      java.nio.file.Files.createTempFile("binary-resource", null),
                      StandardCopyOption.REPLACE_EXISTING)
                  .toFile());
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void accept(String name) {
      parse(model, name).addReference(declaredResource);
    }

    @Override
    public void accept(int value) {
      if (isInDeclaredPackages(value)) { // ignore references outside of scanned packages.
        declaredResource.addReference(model.getResource(value));
      }
    }

    /** Tests if the id is in any of the scanned packages. */
    private boolean isInDeclaredPackages(int value) {
      return packageIds.contains(value >> 24);
    }
  }
}
