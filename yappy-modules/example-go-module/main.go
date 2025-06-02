package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"strings"

	"google.golang.org/grpc"
	"google.golang.org/protobuf/types/known/emptypb"
	"google.golang.org/protobuf/types/known/structpb"

	// These would be your generated proto imports
	pb "github.com/krickert/yappy/example-go-module/proto"
)

type UpperCaseModule struct {
	pb.UnimplementedPipeStepProcessorServer
}

// GetServiceRegistration returns metadata about this module
func (s *UpperCaseModule) GetServiceRegistration(ctx context.Context, req *emptypb.Empty) (*pb.ServiceMetadata, error) {
	log.Println("GetServiceRegistration called")

	// Define configuration schema
	schema := map[string]interface{}{
		"$schema": "http://json-schema.org/draft-07/schema#",
		"type":    "object",
		"properties": map[string]interface{}{
			"fields_to_uppercase": map[string]interface{}{
				"type":        "array",
				"description": "List of fields to convert to uppercase",
				"items": map[string]interface{}{
					"type": "string",
				},
				"default": []string{"title", "body"},
			},
			"add_marker": map[string]interface{}{
				"type":        "boolean",
				"description": "Add [UPPERCASED] marker to processed fields",
				"default":     false,
			},
		},
	}

	schemaJSON, _ := json.MarshalIndent(schema, "", "  ")

	return &pb.ServiceMetadata{
		PipeStepName: "uppercase-converter",
		ContextParams: map[string]string{
			"module_version":     "1.0.0",
			"module_language":    "go",
			"description":        "Converts specified fields to uppercase",
			"author":             "Example Go Developer",
			"json_config_schema": string(schemaJSON),
		},
	}, nil
}

// ProcessData processes a document
func (s *UpperCaseModule) ProcessData(ctx context.Context, req *pb.ProcessRequest) (*pb.ProcessResponse, error) {
	log.Printf("ProcessData called for document: %s", req.Document.Id)

	// Check if test mode
	isTest := false
	if params, ok := req.Metadata.ContextParams["_test_mode"]; ok && params == "true" {
		isTest = true
	}
	if strings.HasPrefix(req.Document.Id, "test-doc-") {
		isTest = true
	}

	if isTest {
		return &pb.ProcessResponse{
			Success: true,
			ProcessorLogs: []string{
				"[TEST] Module validation successful",
				"[TEST] Go module ready",
			},
		}, nil
	}

	// Extract configuration
	fieldsToUppercase := []string{"title", "body"} // defaults
	addMarker := false

	if req.Config != nil && req.Config.CustomJsonConfig != nil {
		if fields, ok := req.Config.CustomJsonConfig.Fields["fields_to_uppercase"]; ok {
			if listValue := fields.GetListValue(); listValue != nil {
				fieldsToUppercase = []string{}
				for _, v := range listValue.Values {
					fieldsToUppercase = append(fieldsToUppercase, v.GetStringValue())
				}
			}
		}
		if marker, ok := req.Config.CustomJsonConfig.Fields["add_marker"]; ok {
			addMarker = marker.GetBoolValue()
		}
	}

	// Process document
	outputDoc := req.Document // In real implementation, you'd deep copy

	processedFields := []string{}

	// Process each configured field
	for _, field := range fieldsToUppercase {
		switch field {
		case "title":
			if outputDoc.Title != nil {
				original := outputDoc.GetTitle()
				uppercased := strings.ToUpper(original)
				if addMarker {
					uppercased = "[UPPERCASED] " + uppercased
				}
				outputDoc.Title = &uppercased
				processedFields = append(processedFields, "title")
			}
		case "body":
			if outputDoc.Body != nil {
				original := outputDoc.GetBody()
				uppercased := strings.ToUpper(original)
				if addMarker {
					uppercased = "[UPPERCASED] " + uppercased
				}
				outputDoc.Body = &uppercased
				processedFields = append(processedFields, "body")
			}
		}
	}

	return &pb.ProcessResponse{
		Success:   true,
		OutputDoc: outputDoc,
		ProcessorLogs: []string{
			fmt.Sprintf("Successfully uppercased %d fields", len(processedFields)),
			fmt.Sprintf("Processed fields: %s", strings.Join(processedFields, ", ")),
		},
	}, nil
}

func main() {
	port := flag.String("port", "50052", "Port to listen on")
	flag.Parse()

	lis, err := net.Listen("tcp", ":"+*port)
	if err != nil {
		log.Fatalf("Failed to listen: %v", err)
	}

	s := grpc.NewServer()
	pb.RegisterPipeStepProcessorServer(s, &UpperCaseModule{})

	log.Printf("Uppercase Module (Go) listening on port %s", *port)

	// Optional: Register with Consul
	if os.Getenv("CONSUL_ENABLED") == "true" {
		registerWithConsul(*port)
	}

	if err := s.Serve(lis); err != nil {
		log.Fatalf("Failed to serve: %v", err)
	}
}

func registerWithConsul(port string) {
	// Simplified - in production you'd use the Consul API client
	log.Println("Consul registration would happen here")
}