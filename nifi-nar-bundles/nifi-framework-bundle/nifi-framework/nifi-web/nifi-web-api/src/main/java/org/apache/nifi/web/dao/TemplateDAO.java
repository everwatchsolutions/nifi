/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.web.dao;

import org.apache.nifi.controller.Template;
import org.apache.nifi.web.api.dto.FlowSnippetDTO;
import org.apache.nifi.web.api.dto.TemplateDTO;

import java.util.Set;

public interface TemplateDAO {

    /**
     * Creates a template.
     *
     * @param templateDTO The template DTO
     * @param groupId the ID of the group to add the template to
     * @return The template
     */
    Template createTemplate(TemplateDTO templateDTO, String groupId);

    /**
     * Import the specified template.
     *
     * @param templateDTO dto
     * @param groupId the ID of the group to add the template to
     * @return template
     */
    Template importTemplate(TemplateDTO templateDTO, String groupId);

    /**
     * Instantiate the corresponding template.
     *
     * @param groupId group id
     * @param originX x
     * @param originY y
     * @param templateId template id
     * @return flow snippet
     */
    FlowSnippetDTO instantiateTemplate(String groupId, Double originX, Double originY, String templateId);

    /**
     * Gets the specified template.
     *
     * @param templateId The template id
     * @return The template
     */
    Template getTemplate(String templateId);

    /**
     * Gets all of the templates.
     *
     * @return The templates
     */
    Set<Template> getTemplates();

    /**
     * Deletes the specified template.
     *
     * @param templateId The template id
     */
    void deleteTemplate(String templateId);
}
