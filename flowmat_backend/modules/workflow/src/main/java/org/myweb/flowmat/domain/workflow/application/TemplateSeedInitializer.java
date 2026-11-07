package org.myweb.flowmat.domain.workflow.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessTemplate;
import org.myweb.flowmat.domain.workflow.domain.entity.WorkflowTemplate;
import org.myweb.flowmat.domain.workflow.repository.ProcessTemplateRepository;
import org.myweb.flowmat.domain.workflow.repository.WorkflowTemplateRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateSeedInitializer implements ApplicationRunner {

    private final WorkflowTemplateRepository workflowTemplateRepository;
    private final ProcessTemplateRepository processTemplateRepository;

    @Override
    public void run(ApplicationArguments args) {
        try {
            seedWorkflowTemplates();
            seedProcessTemplates();
        } catch (Exception e) {
            log.warn("Template seed skipped: {}", e.getMessage());
        }
    }

    private void seedWorkflowTemplates() {
        ensureWorkflowTemplate(
            "wf_tpl_mfg_main",
            "Manufacturing Main Flow",
            "manufacturing",
            "main",
            "factory",
            "Manufacturing flow for material transformation and packaging.",
            "{\"domain\":\"manufacturing\",\"sample\":\"main\"}",
            10
        );
        ensureWorkflowTemplate(
            "wf_tpl_software_pipeline",
            "Software Data Pipeline",
            "software_pipeline",
            "main",
            "pipeline",
            "Pipeline for parsing, validating, transforming, and storing data.",
            "{\"domain\":\"software\",\"sample\":\"pipeline\"}",
            20
        );
        ensureWorkflowTemplate(
            "wf_tpl_restaurant_service",
            "Restaurant Service Flow",
            "restaurant",
            "main",
            "restaurant",
            "Kitchen and service flow from prep to serving.",
            "{\"domain\":\"restaurant\",\"sample\":\"service\"}",
            30
        );
        ensureWorkflowTemplate(
            "wf_tpl_logistics_fulfillment",
            "Logistics Fulfillment Flow",
            "logistics",
            "main",
            "warehouse",
            "Warehouse flow for receive, store, pick, pack, and ship.",
            "{\"domain\":\"logistics\",\"sample\":\"fulfillment\"}",
            40
        );
    }

    private void seedProcessTemplates() {
        ensureProcessTemplate("pr_tpl_mfg_mixing", "Mixing Node", "mixing", "process", "blend", "amber", "Blend raw materials.", 10);
        ensureProcessTemplate("pr_tpl_mfg_heating", "Heating Node", "heating", "process", "heat", "red", "Apply heat treatment.", 20);
        ensureProcessTemplate("pr_tpl_mfg_packaging", "Packaging Node", "packaging", "process", "package", "emerald", "Package finished goods.", 30);

        ensureProcessTemplate("pr_tpl_sw_parser", "Parser Node", "parse", "process", "parse", "sky", "Parse incoming payloads.", 110);
        ensureProcessTemplate("pr_tpl_sw_validator", "Validator Node", "validate", "process", "validate", "violet", "Validate schema and business rules.", 120);
        ensureProcessTemplate("pr_tpl_sw_transformer", "Transformer Node", "normalize", "process", "transform", "indigo", "Normalize and transform data.", 130);
        ensureProcessTemplate("pr_tpl_sw_storage", "Storage Node", "save", "storage", "storage", "slate", "Persist results to storage.", 140);

        ensureProcessTemplate("pr_tpl_rest_prep", "Prep Station", "prep", "process", "prep", "lime", "Prepare ingredients and tools.", 210);
        ensureProcessTemplate("pr_tpl_rest_cook", "Cooking Station", "cook", "process", "cook", "orange", "Cook dishes to target state.", 220);
        ensureProcessTemplate("pr_tpl_rest_plate", "Plating Station", "plate", "process", "plate", "pink", "Assemble and plate dishes.", 230);
        ensureProcessTemplate("pr_tpl_rest_serve", "Serving Area", "serve", "output", "serve", "rose", "Deliver dishes to customers.", 240);

        ensureProcessTemplate("pr_tpl_log_receive", "Receiving Dock", "receive", "input", "receive", "cyan", "Receive inbound items.", 310);
        ensureProcessTemplate("pr_tpl_log_store", "Storage Rack", "store", "storage", "store", "blue", "Store inventory in rack.", 320);
        ensureProcessTemplate("pr_tpl_log_pick", "Picking Zone", "pick", "process", "pick", "teal", "Pick items for orders.", 330);
        ensureProcessTemplate("pr_tpl_log_pack", "Packing Station", "pack", "process", "pack", "yellow", "Pack and label shipments.", 340);
        ensureProcessTemplate("pr_tpl_log_ship", "Shipping Dock", "ship", "output", "ship", "stone", "Ship outbound orders.", 350);
    }

    private void ensureWorkflowTemplate(
        String templateId,
        String templateName,
        String templateCategory,
        String templateType,
        String iconKey,
        String defaultDesc,
        String defaultConfig,
        int sortOrder
    ) {
        if (workflowTemplateRepository.existsById(templateId)) {
            return;
        }

        WorkflowTemplate template = new WorkflowTemplate();
        template.setTemplateId(templateId);
        template.setTemplateName(templateName);
        template.setTemplateCategory(templateCategory);
        template.setTemplateType(templateType);
        template.setIconKey(iconKey);
        template.setDefaultWidth(240.0);
        template.setDefaultHeight(120.0);
        template.setDefaultDesc(defaultDesc);
        template.setDefaultConfig(defaultConfig);
        template.setPublicYn("Y");
        template.setSortOrder(sortOrder);
        workflowTemplateRepository.save(template);
    }

    private void ensureProcessTemplate(
        String templateId,
        String templateName,
        String templateCategory,
        String templateType,
        String iconKey,
        String defaultColorScheme,
        String defaultDesc,
        int sortOrder
    ) {
        if (processTemplateRepository.existsById(templateId)) {
            return;
        }

        ProcessTemplate template = new ProcessTemplate();
        template.setTemplateId(templateId);
        template.setTemplateName(templateName);
        template.setTemplateCategory(templateCategory);
        template.setTemplateType(templateType);
        template.setIconKey(iconKey);
        template.setDefaultColorScheme(defaultColorScheme);
        template.setDefaultWidth(160.0);
        template.setDefaultHeight(80.0);
        template.setDefaultDesc(defaultDesc);
        template.setDefaultConfig("{\"seeded\":true}");
        template.setPublicYn("Y");
        template.setSortOrder(sortOrder);
        processTemplateRepository.save(template);
    }
}
