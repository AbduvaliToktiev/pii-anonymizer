package ru.pii.anonymizer.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class RequestFilesTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void filesAndToolsOfLatestTurnAreListed() throws Exception {
        ObjectNode request = (ObjectNode) json.readTree("""
                {"messages":[
                  {"role":"user","content":"Посчитай зарплаты"},
                  {"role":"assistant","content":[
                    {"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"C:\\\\work\\\\Зарплаты.xlsx"}},
                    {"type":"tool_use","id":"t2","name":"Bash","input":{"command":"ls"}},
                    {"type":"tool_use","id":"t3","name":"Read","input":{"file_path":"C:\\\\work\\\\Зарплаты.xlsx"}}
                  ]},
                  {"role":"user","content":[
                    {"type":"tool_result","tool_use_id":"t1","content":"..."},
                    {"type":"tool_result","tool_use_id":"t2","content":"..."},
                    {"type":"tool_result","tool_use_id":"t3","content":"..."}
                  ]}
                ]}""");

        assertThat(RequestFiles.latestTurn(request)).containsExactly("C:\\work\\Зарплаты.xlsx", "Bash");
    }

    @Test
    void plainUserMessageHasNoFiles() throws Exception {
        ObjectNode request = (ObjectNode) json.readTree("""
                {"messages":[{"role":"user","content":[{"type":"text","text":"Привет"}]}]}""");

        assertThat(RequestFiles.latestTurn(request)).isEmpty();
    }
}
